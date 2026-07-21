#!/usr/bin/env bash
# KubeOnCall MySQL backup (WBS-12 §6).
#
# Takes a consistent logical backup of the kubeoncall business fact store with --single-transaction
# (no InnoDB read lock) and --source-data for PITR coordinates. Output is gzip-compressed and named
# with a UTC timestamp so retention tooling can age it out. Secrets come from the environment or the
# standard MySQL_* env vars; never hardcode credentials here.
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

mkdir -p "${OUTPUT_DIR}"
echo "[backup] ${USER}@${HOST}:${PORT}/${DATABASE} -> ${OUTPUT_FILE}"

# --single-transaction: consistent InnoDB snapshot without blocking writes.
# --routines --triggers --events: capture stored logic so restore is complete.
# --column-statistics=0: compatibility with MySQL 8 clients against older-compatible servers.
mysqldump \
  --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" \
  --single-transaction --quick --routines --triggers --events \
  --column-statistics=0 \
  "${DATABASE}" | gzip > "${OUTPUT_FILE}"

SIZE="$(wc -c < "${OUTPUT_FILE}")"
echo "[backup] done: ${OUTPUT_FILE} (${SIZE} bytes)"
echo "[backup] verify with: gunzip -c ${OUTPUT_FILE} | mysql --host=... -e 'source /dev/stdin'"
