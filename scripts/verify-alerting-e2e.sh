#!/usr/bin/env sh
set -eu

: "${ALERTMANAGER_WEBHOOK_TOKEN:?Set ALERTMANAGER_WEBHOOK_TOKEN before running this script.}"

compose="docker compose --profile alerting"
prometheus_url="${PROMETHEUS_URL:-http://localhost:9090}"
kubeoncall_url="${KUBEONCALL_URL:-http://localhost:8080}"
stream_key="${ALARM_INBOX_STREAM_KEY:-kubeoncall:alarm:inbox}"
node_exporter_stopped=false
notification_adapter_pid=""
notification_log="${NOTIFICATION_LOG:-/tmp/kubeoncall-alerting-e2e-notifications.jsonl}"

cleanup() {
  if [ "$node_exporter_stopped" = true ]; then
    $compose start node-exporter >/dev/null
  fi
  if [ -n "$notification_adapter_pid" ]; then
    kill "$notification_adapter_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

start_notification_adapter() {
  if curl --silent --fail http://localhost:18083/health >/dev/null 2>&1; then
    return
  fi
  : >"$notification_log"
  python3 scripts/mock-notification-adapter.py --log "$notification_log" &
  notification_adapter_pid=$!
  for _ in $(seq 1 20); do
    curl --silent --fail http://localhost:18083/health >/dev/null 2>&1 && return
    sleep 1
  done
  printf '%s\n' 'Timed out starting the local notification receiver' >&2
  return 1
}

query_alert_state() {
  curl --silent --show-error --fail --get "$prometheus_url/api/v1/query" --data-urlencode 'query=ALERTS{alertname="NodeDown"}' \
    | jq -r '.data.result[0].metric.alertstate // "inactive"'
}

post_webhook() {
  payload="$1"
  curl --silent --show-error --output /tmp/kubeoncall-alert-e2e-response.json --write-out '%{http_code}' \
    --request POST "$kubeoncall_url/api/integrations/alertmanager/webhook" \
    --header 'Content-Type: application/json' --header "Authorization: Bearer $ALERTMANAGER_WEBHOOK_TOKEN" --data "$payload"
}

wait_for_state() {
  wanted="$1"
  attempts="${2:-36}"
  for _ in $(seq 1 "$attempts"); do
    state="$(query_alert_state)"
    [ "$state" = "$wanted" ] && return 0
    sleep 10
  done
  printf 'Timed out waiting for NodeDown state %s; current=%s\n' "$wanted" "$(query_alert_state)" >&2
  return 1
}

wait_for_inactive() {
  for _ in $(seq 1 18); do
    [ "$(query_alert_state)" = inactive ] && return 0
    sleep 10
  done
  printf '%s\n' 'Timed out waiting for NodeDown to resolve' >&2
  return 1
}

wait_for_inbox_growth() {
  previous="$1"
  for _ in $(seq 1 18); do
    current="$($compose exec -T redis redis-cli XLEN "$stream_key")"
    [ "$current" -gt "$previous" ] && return 0
    sleep 5
  done
  printf 'Timed out waiting for KubeOnCall Inbox growth; previous=%s current=%s\n' \
    "$previous" "$($compose exec -T redis redis-cli XLEN "$stream_key")" >&2
  return 1
}

wait_for_notification() {
  for _ in $(seq 1 20); do
    if [ -f "$notification_log" ] && grep -q '"alertName": "NodeDown"' "$notification_log"; then
      return 0
    fi
    sleep 1
  done
  printf '%s\n' 'Timed out waiting for the KubeOnCall notification request' >&2
  return 1
}

start_notification_adapter

printf '%s\n' 'Checking Node Exporter scrape target...'
curl --silent --show-error --fail --get "$prometheus_url/api/v1/query" --data-urlencode 'query=up{job="node-exporter"}' \
  | jq -e '.data.result | length > 0 and .[0].value[1] == "1"' >/dev/null

printf '%s\n' 'Verifying bearer rejection...'
unauthorized="$(curl --silent --output /dev/null --write-out '%{http_code}' --request POST \
  "$kubeoncall_url/api/integrations/alertmanager/webhook" --header 'Content-Type: application/json' --data '{"version":"4","alerts":[]}')"
[ "$unauthorized" = 401 ]

started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
firing_payload="{\"version\":\"4\",\"groupKey\":\"{}:{alertname=NodeDown}\",\"status\":\"firing\",\"receiver\":\"kubeoncall-webhook\",\"alerts\":[{\"status\":\"firing\",\"labels\":{\"alertname\":\"NodeDown\",\"severity\":\"P1\",\"cluster\":\"local\",\"node\":\"docker-host\"},\"annotations\":{\"summary\":\"KubeOnCall webhook E2E\"},\"startsAt\":\"$started_at\",\"endsAt\":\"0001-01-01T00:00:00Z\",\"fingerprint\":\"kubeoncall-e2e-fingerprint\"}]}"

printf '%s\n' 'Verifying durable acceptance and duplicate suppression...'
[ "$(post_webhook "$firing_payload")" = 202 ]
jq -e '.accepted == 1 and .duplicates == 0' /tmp/kubeoncall-alert-e2e-response.json >/dev/null
[ "$(post_webhook "$firing_payload")" = 202 ]
jq -e '.accepted == 0 and .duplicates == 1' /tmp/kubeoncall-alert-e2e-response.json >/dev/null
wait_for_notification

resolved_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
resolved_payload="$(printf '%s' "$firing_payload" | sed "s/\\\"status\\\":\\\"firing\\\"/\\\"status\\\":\\\"resolved\\\"/g; s/0001-01-01T00:00:00Z/$resolved_at/")"
[ "$(post_webhook "$resolved_payload")" = 202 ]
jq -e '.accepted == 1 and .duplicates == 0' /tmp/kubeoncall-alert-e2e-response.json >/dev/null

printf '%s\n' 'Creating a real NodeDown pending -> firing -> resolved cycle...'
inbox_before_cycle="$($compose exec -T redis redis-cli XLEN "$stream_key")"
$compose stop node-exporter >/dev/null
node_exporter_stopped=true
wait_for_state pending
wait_for_state firing
wait_for_inbox_growth "$inbox_before_cycle"
inbox_after_firing="$($compose exec -T redis redis-cli XLEN "$stream_key")"

$compose start node-exporter >/dev/null
node_exporter_stopped=false
wait_for_inactive
wait_for_inbox_growth "$inbox_after_firing"

inbox_events="$($compose exec -T redis redis-cli XLEN "$stream_key")"
stream_events="$($compose exec -T redis redis-cli --raw XRANGE "$stream_key" - +)"
printf '%s\n' "$stream_events" | grep -q '"alertName":"NodeDown".*"status":"firing"'
printf '%s\n' "$stream_events" | grep -q '"alertName":"NodeDown".*"status":"resolved"'
printf 'alerting-e2e: passed (auth/dedup/notification; NodeDown pending/firing/resolved; inboxEvents=%s)\n' \
  "$inbox_events"
