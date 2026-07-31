#!/usr/bin/env bash
# KubeOnCall MySQL point-in-time replay (WBS-12 §6).
#
# Apply archived binary logs to an isolated database restored from mysql-restore.sh. This script
# deliberately refuses the default production database and requires the target name as an explicit
# confirmation value. It does not fetch, retain or delete binlogs: archive retention remains the
# responsibility of the MySQL platform.
#
# Usage:
#   CONFIRM_PITR_REPLAY=kubeoncall_pitr MYSQL_HOST=... MYSQL_USER=... MYSQL_PASSWORD=... \
#     ./scripts/mysql-pitr-replay.sh kubeoncall_pitr '2026-07-23 12:00:00' /secure/binlog.000001 [...]
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "Usage: $0 <target-database> <stop-datetime-utc> <binlog> [binlog...]" >&2
  exit 2
fi

TARGET_DB="$1"
STOP_DATETIME="$2"
shift 2
HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
USER="${MYSQL_USER:?MYSQL_USER is required}"
PASSWORD="${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"

if [ "${CONFIRM_PITR_REPLAY:-}" != "${TARGET_DB}" ]; then
  echo "[pitr] REFUSING: set CONFIRM_PITR_REPLAY=${TARGET_DB}" >&2
  exit 2
fi
if [ "${TARGET_DB}" = "${MYSQL_DATABASE:-kubeoncall}" ] || [ "${TARGET_DB}" = "kubeoncall" ]; then
  echo "[pitr] REFUSING: replay only into an isolated restore database, never kubeoncall" >&2
  exit 2
fi
for binlog in "$@"; do
  if [ ! -r "${binlog}" ]; then
    echo "[pitr] unreadable binlog: ${binlog}" >&2
    exit 2
  fi
done

echo "[pitr] replaying $# archived binlog(s) into ${USER}@${HOST}:${PORT}/${TARGET_DB} through ${STOP_DATETIME} UTC"
mysqlbinlog --stop-datetime="${STOP_DATETIME}" "$@" | mysql \
  --host="${HOST}" --port="${PORT}" --user="${USER}" --password="${PASSWORD}" "${TARGET_DB}"
echo "[pitr] completed. Record the restored row counts and the requested recovery timestamp before serving traffic."
