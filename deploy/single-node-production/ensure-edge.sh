#!/usr/bin/env bash
set -euo pipefail

lock_file="/home/jsa/kubeoncall-backups/.edge-watch.lock"
exec 9>"$lock_file"
if ! flock -n 9; then
  exit 0
fi

if curl -fsS --connect-timeout 2 --max-time 5 http://127.0.0.1:18082/ >/dev/null 2>&1; then
  exit 0
fi

docker restart kubeoncall-tunnel >/dev/null
tunnel_status=000
for attempt in $(seq 1 30); do
  tunnel_status=$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 http://127.0.0.1:18082/ 2>/dev/null || true)
  [ "$tunnel_status" = 200 ] && break
  sleep 1
done
[ "$tunnel_status" = 200 ]

docker restart kubeoncall-edge-nginx >/dev/null
https_status=000
for attempt in $(seq 1 20); do
  https_status=$(curl -ksS -o /dev/null -w '%{http_code}' --connect-timeout 2 --max-time 5 https://127.0.0.1/ 2>/dev/null || true)
  [ "$https_status" = 200 ] && break
  sleep 1
done
[ "$https_status" = 200 ]
printf 'edge recovered at %s\n' "$(date -Is)"
