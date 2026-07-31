#!/usr/bin/env sh
set -eu

kubeoncall_url="${KUBEONCALL_URL:-http://localhost:8080}"

response="$(curl --silent --show-error --fail-with-body --max-time 90 \
  --request POST "$kubeoncall_url/api/ask" \
  --header 'Content-Type: application/json' \
  --data '{"question":"Who owns payment-service?","sessionId":"dynamic-mcp-e2e"}')"

printf '%s' "$response" | jq -e '
  any(.details.planner.plannerKnowledge.dynamicMcpInvocations[];
    .tool == "inventory.lookupOwner"
    and .status == "success"
    and .parameters.serviceName == "payment-service"
    and .response.owner == "payments-platform"
    and .response.source == "dynamic-mcp-e2e")
' >/dev/null

printf 'dynamic-mcp: passed (tool=inventory.lookupOwner, owner=payments-platform)\n'
