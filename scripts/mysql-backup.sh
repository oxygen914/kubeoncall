#!/usr/bin/env bash
# KubeOnCall MySQL backup (WBS-12 §6).
#
# Takes a consistent logical backup of the kubeoncall business fact store with --single-transaction
# (no InnoDB read lock) and --source-data for PITR coordinates. The compressed output is verified
# before publication, then receives a SHA-256 sidecar so a restore can reject corrupt or incomplete
# backups. Files are named with a UTC timestamp so retention tooling can age them out. Secrets come
# from the environment or the standard MySQL_* env vars; never hardcode credentials here.
#
# Usage:
#   MYSQL_HOST=... MYSQL_USER=... MYSQL_PASSWORD=... ./scripts/mysql-backup.sh [output-dir]
set -euo pipefail

DATABASE="${MYSQL_DATABASE:-kubeoncall}"
HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
USER="${MYSQL_USER:?MYSQL_USER is required}"
PASSWORD="${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"
OUTPUT_DIR="${1:-./backups}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_FILE="${OUTPUT_DIR}/${DATABASE}-${TIMESTAMP}.sql.gz"
CHECKSUM_FILE="${OUTPUT_FILE}.sha256"
TEMP_FILE=""

mkdir -p "${OUTPUT_DIR}"
echo "[backup] ${USER}@${HOST}:${PORT}/${DATABASE} -> ${OUTPUT_FILE}"

cleanup() {
  if [ -n "${TEMP_FILE}" ] && [ -f "${TEMP_FILE}" ]; then
    rm -f "${TEMP_FILE}"
  fi
}
trap cleanup EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
    return
  fi
  echo "[backup] sha256sum or shasum is required" >&2
  return 1
}

# --single-transaction: consistent InnoDB snapshot without blocking writes.
# --routines --triggers --events: capture stored logic so restore is complete.
# --source-data=2: records binlog file/position as a commented CHANGE REPLICATION SOURCE statement
#   in the dump; this is the coordinate for a subsequent PITR replay, while keeping normal restores
#   from automatically reconfiguring replication.
# --column-statistics=0: compatibility with MySQL 8 clients against older-compatible servers.
TEMP_FILE="$(mktemp "${OUTPUT_DIR}/.${DATABASE}-${TIMESTAMP}.XXXXXX")"
mysqldump \
  --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" \
  --single-transaction --quick --routines --triggers --events \
  --source-data=2 \
  --column-statistics=0 \
  "${DATABASE}" | gzip > "${TEMP_FILE}"

gzip -t "${TEMP_FILE}"
mv "${TEMP_FILE}" "${OUTPUT_FILE}"
TEMP_FILE=""

SIZE="$(wc -c < "${OUTPUT_FILE}")"
SHA256="$(sha256 "${OUTPUT_FILE}")"
printf '%s  %s\n' "${SHA256}" "$(basename "${OUTPUT_FILE}")" > "${CHECKSUM_FILE}"
echo "[backup] done: ${OUTPUT_FILE} (${SIZE} bytes, sha256=${SHA256})"
echo "[backup] checksum: ${CHECKSUM_FILE}"
echo "[backup] verify with: gunzip -c ${OUTPUT_FILE} | mysql --host=... -e 'source /dev/stdin'"
