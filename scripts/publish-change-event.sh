#!/usr/bin/env bash
set -euo pipefail

: "${KUBEONCALL_CHANGE_EVENT_URL:?KUBEONCALL_CHANGE_EVENT_URL is required}"

provider="${KUBEONCALL_CHANGE_EVENT_PROVIDER:-generic}"
event_type="${KUBEONCALL_CHANGE_EVENT_TYPE:-deployment}"
delivery_id="${KUBEONCALL_CHANGE_EVENT_DELIVERY_ID:-${CI_PIPELINE_ID:-${GITHUB_RUN_ID:-manual-$(date +%s)}}}"
payload_file="${1:--}"
auth_headers=()

case "${provider}" in
  github)
    event_header="X-GitHub-Event: ${event_type}"
    delivery_header="X-GitHub-Delivery: ${delivery_id}"
    if [[ -n "${KUBEONCALL_CHANGE_EVENT_GITHUB_SECRET:-}" ]]; then
      if [[ "${payload_file}" == "-" ]]; then
        echo "GitHub HMAC mode requires a payload file so the signed bytes match the request body." >&2
        exit 2
      fi
      signature="$(openssl dgst -sha256 -hmac "${KUBEONCALL_CHANGE_EVENT_GITHUB_SECRET}" -hex "${payload_file}" \
        | awk '{print $NF}')"
      auth_headers+=(--header "X-Hub-Signature-256: sha256=${signature}")
    fi
    ;;
  gitlab)
    event_header="X-Gitlab-Event: ${event_type}"
    delivery_header="X-Gitlab-Event-UUID: ${delivery_id}"
    if [[ -n "${KUBEONCALL_CHANGE_EVENT_GITLAB_TOKEN:-}" ]]; then
      auth_headers+=(--header "X-Gitlab-Token: ${KUBEONCALL_CHANGE_EVENT_GITLAB_TOKEN}")
    fi
    ;;
  jenkins)
    event_header="X-Jenkins-Event: ${event_type}"
    delivery_header="X-Jenkins-Event-Id: ${delivery_id}"
    if [[ -n "${KUBEONCALL_CHANGE_EVENT_JENKINS_TOKEN:-}" ]]; then
      auth_headers+=(--header "X-Jenkins-Token: ${KUBEONCALL_CHANGE_EVENT_JENKINS_TOKEN}")
    fi
    ;;
  argocd)
    event_header="X-ArgoCD-Event: ${event_type}"
    delivery_header="X-ArgoCD-Delivery: ${delivery_id}"
    if [[ -n "${KUBEONCALL_CHANGE_EVENT_ARGOCD_TOKEN:-}" ]]; then
      auth_headers+=(--header "X-ArgoCD-Token: ${KUBEONCALL_CHANGE_EVENT_ARGOCD_TOKEN}")
    fi
    ;;
  generic)
    event_header="X-KubeOnCall-Event: ${event_type}"
    delivery_header="X-KubeOnCall-Delivery: ${delivery_id}"
    ;;
  *)
    echo "Unsupported provider: ${provider}" >&2
    exit 2
    ;;
esac

if [[ -n "${KUBEONCALL_CHANGE_EVENT_TOKEN:-}" ]]; then
  auth_headers+=(--header "Authorization: Bearer ${KUBEONCALL_CHANGE_EVENT_TOKEN}")
fi
if [[ "${#auth_headers[@]}" -eq 0 ]]; then
  echo "Configure KUBEONCALL_CHANGE_EVENT_TOKEN or the provider-specific secret/token." >&2
  exit 2
fi

curl --fail-with-body --silent --show-error \
  --request POST \
  "${auth_headers[@]}" \
  --header "Content-Type: application/json" \
  --header "${event_header}" \
  --header "${delivery_header}" \
  --data-binary "@${payload_file}" \
  "${KUBEONCALL_CHANGE_EVENT_URL%/}/api/integrations/change-events/${provider}"
