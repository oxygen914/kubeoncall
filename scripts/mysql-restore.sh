#!/usr/bin/env bash
# KubeOnCall MySQL restore (WBS-12 §6).
#
# Restores a backup produced by mysql-backup.sh into a target database. Destructive: overwrites
# existing rows. The script checks gzip integrity and, when supplied by the backup script, its
# SHA-256 sidecar before it makes any database change. It refuses to run unless CONFIRM_RESTORE
# matches the target database name, so an operator cannot accidentally clobber production by
# running the wrong command.
#
# Usage:
#   CONFIRM_RESTORE=kubeoncall MYSQL_HOST=... MYSQL_USER=... MYSQL_PASSWORD=... \
#     ./scripts/mysql-restore.sh <backup-file.sql.gz> [target-database]
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <backup-file.sql.gz> [target-database]" >&2
  exit 2
fi
BACKUP_FILE="$1"
TARGET_DB="${2:-${MYSQL_DATABASE:-kubeoncall}}"
HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
USER="${MYSQL_USER:?MYSQL_USER is required}"
PASSWORD="${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"

if [ "${CONFIRM_RESTORE:-}" != "${TARGET_DB}" ]; then
  echo "[restore] REFUSING: set CONFIRM_RESTORE=${TARGET_DB} to confirm overwrite of ${TARGET_DB}" >&2
  exit 2
fi
if [ ! -f "${BACKUP_FILE}" ]; then
  echo "[restore] backup file not found: ${BACKUP_FILE}" >&2
  exit 2
fi

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
    return
  fi
  echo "[restore] sha256sum or shasum is required" >&2
  return 1
}

echo "[restore] checking compressed backup integrity"
gzip -t "${BACKUP_FILE}"
CHECKSUM_FILE="${BACKUP_FILE}.sha256"
if [ -f "${CHECKSUM_FILE}" ]; then
  EXPECTED_SHA256="$(awk 'NR == 1 { print $1 }' "${CHECKSUM_FILE}")"
  ACTUAL_SHA256="$(sha256 "${BACKUP_FILE}")"
  if [ -z "${EXPECTED_SHA256}" ] || [ "${EXPECTED_SHA256}" != "${ACTUAL_SHA256}" ]; then
    echo "[restore] REFUSING: checksum mismatch for ${BACKUP_FILE}" >&2
    exit 2
  fi
  echo "[restore] checksum verified: ${CHECKSUM_FILE}"
else
  echo "[restore] WARNING: no checksum sidecar found; continuing after gzip integrity check" >&2
fi

echo "[restore] ${BACKUP_FILE} -> ${USER}@${HOST}:${PORT}/${TARGET_DB}"
echo "[restore] WARNING: this overwrites existing data in ${TARGET_DB}."
echo "[restore] starting in 5 seconds (Ctrl-C to abort)..."
sleep 5

# Create the target database if missing (restore into a fresh environment), then pipe the dump in.
mysql --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" \
  -e "CREATE DATABASE IF NOT EXISTS \`${TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

gunzip -c "${BACKUP_FILE}" | mysql --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" "${TARGET_DB}"

echo "[restore] done. Verify row counts against the source before serving traffic."
