#!/usr/bin/env bash
# KubeOnCall MySQL restore (WBS-12 §6).
#
# Restores a backup produced by mysql-backup.sh into a target database. Destructive: overwrites
# existing rows. The script refuses to run unless CONFIRM_RESTORE matches the target database name,
# so an operator cannot accidentally clobber production by running the wrong command.
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

echo "[restore] ${BACKUP_FILE} -> ${USER}@${HOST}:${PORT}/${TARGET_DB}"
echo "[restore] WARNING: this overwrites existing data in ${TARGET_DB}."
echo "[restore] starting in 5 seconds (Ctrl-C to abort)..."
sleep 5

# Create the target database if missing (restore into a fresh environment), then pipe the dump in.
mysql --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" \
  -e "CREATE DATABASE IF NOT EXISTS \`${TARGET_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

gunzip -c "${BACKUP_FILE}" | mysql --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" "${TARGET_DB}"

echo "[restore] done. Verify row counts against the source before serving traffic."
