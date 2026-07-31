#!/usr/bin/env sh
set -eu

kubeoncall_url="${KUBEONCALL_URL:-http://localhost:8080}"

submission="$(curl --silent --show-error --fail-with-body --max-time 30 \
  --request POST "$kubeoncall_url/api/memory/extractions" \
  --header 'Content-Type: application/json' \
  --data '{"memoryType":"SERVICE_FACT","scope":"SERVICE","subject":"payment-service ownership","content":"payment-service is permanently owned by the payments-platform team. This ownership was verified in service catalog ticket OPS-42.","service":"payment-service","metadata":{"source":"aliyun-memory-e2e","ticket":"OPS-42"}}')"
task_id="$(printf '%s' "$submission" | jq -r '.taskId')"
[ -n "$task_id" ]
[ "$task_id" != null ]

status_json=''
for _ in $(seq 1 30); do
  status_json="$(curl --silent --show-error --fail-with-body --max-time 15 \
    "$kubeoncall_url/api/memory/extractions/$task_id")"
  status="$(printf '%s' "$status_json" | jq -r '.status')"
  case "$status" in
    COMPLETED) break ;;
    DEAD_LETTER) printf '%s\n' "$status_json" >&2; exit 1 ;;
  esac
  sleep 2
done

printf '%s' "$status_json" | jq -e \
  '.status == "COMPLETED" and .extractionMode == "llm_structured" and .extractedCount > 0 and (.memoryIds | length) > 0' \
  >/dev/null
memory_id="$(printf '%s' "$status_json" | jq -r '.memoryIds[0]')"

search="$(curl --silent --show-error --fail-with-body --max-time 60 \
  --request POST "$kubeoncall_url/api/memory/search" \
  --header 'Content-Type: application/json' \
  --data '{"query":"Who owns payment-service?","filters":{"service":"payment-service"},"topK":5,"includeTrace":true}')"
printf '%s' "$search" | jq -e --arg memory_id "$memory_id" \
  'any(.entries[]; .id == $memory_id and .metadata.extraction_mode == "llm_structured" and (.metadata.quality_score | tonumber) >= 0.55)' \
  >/dev/null

curl --silent --show-error --fail-with-body --max-time 30 \
  --request POST "$kubeoncall_url/api/knowledge/$memory_id/delete" \
  --header 'Content-Type: application/json' \
  --data '{"reason":"completed aliyun memory pipeline validation"}' >/dev/null

printf 'memory-pipeline: passed (task=%s, mode=llm_structured, memory=%s)\n' "$task_id" "$memory_id"
