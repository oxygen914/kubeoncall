#!/usr/bin/env bash
set -euo pipefail

release="${KUBEONCALL_HELM_RELEASE:-kubeoncall}"
namespace="${KUBEONCALL_HELM_NAMESPACE:-kubeoncall}"
values_file="${KUBEONCALL_HELM_VALUES:-deploy/helm/kubeoncall/values-production.yaml}"
extra_values_file="${KUBEONCALL_HELM_EXTRA_VALUES:-}"
apply="${KUBEONCALL_HELM_APPLY:-false}"
temporary_directory="$(mktemp -d)"
port_forward_pid=""

cleanup() {
  if [[ -n "${port_forward_pid}" ]]; then
    kill "${port_forward_pid}" >/dev/null 2>&1 || true
    wait "${port_forward_pid}" >/dev/null 2>&1 || true
  fi
  rm -rf "${temporary_directory}"
}
trap cleanup EXIT

rendered_file="${temporary_directory}/rendered.yaml"
server_dry_run_file="${temporary_directory}/server-dry-run.txt"

values_args=(--values "${values_file}")
if [[ -n "${extra_values_file}" ]]; then
  values_args+=(--values "${extra_values_file}")
fi

prepare_local_image() {
  local values_path="$1"
  local repository="$2"
  local source_image="$3"
  local image_id image_tag target_image

  image_id="$(docker image inspect --format='{{.Id}}' "${source_image}")"
  image_tag="local-${image_id#sha256:}"
  image_tag="${image_tag:0:18}"
  target_image="${repository}:${image_tag}"
  docker tag "${source_image}" "${target_image}"
  values_args+=(
    --set-string "${values_path}.repository=${repository}"
    --set-string "${values_path}.tag=${image_tag}"
    --set-string "${values_path}.digest="
    --set-string "${values_path}.pullPolicy=Never"
  )
}

if [[ "${KUBEONCALL_HELM_LOAD_LOCAL_IMAGE:-false}" == "true" ]]; then
  # Every local image is retagged from its content ID. The resulting immutable Pod template avoids
  # stale mutable-tag cache hits and forces a rollout whenever image contents change.
  helm template "${release}" deploy/helm/kubeoncall \
    --namespace "${namespace}" \
    "${values_args[@]}" >"${temporary_directory}/preliminary.yaml"
  prepare_local_image image kubeoncall-backend "${KUBEONCALL_BACKEND_LOCAL_IMAGE:-kubeoncall-backend:latest}"
  if grep -q 'app.kubernetes.io/component: console' "${temporary_directory}/preliminary.yaml"; then
    prepare_local_image console.image kubeoncall-console "${KUBEONCALL_CONSOLE_LOCAL_IMAGE:-kubeoncall-console:latest}"
  fi
  if grep -q '^  name: sandbox-controller$' "${temporary_directory}/preliminary.yaml"; then
    prepare_local_image sandboxController.image kubeoncall-sandbox-controller \
      "${KUBEONCALL_SANDBOX_CONTROLLER_LOCAL_IMAGE:-kubeoncall-sandbox-controller:latest}"
  fi
fi

helm lint --strict deploy/helm/kubeoncall
helm template "${release}" deploy/helm/kubeoncall \
  --namespace "${namespace}" \
  "${values_args[@]}" >"${rendered_file}"

secret_name="$(awk '
  /secretKeyRef:/ { in_secret = 1; next }
  in_secret && $1 == "name:" { print $2; exit }
 ' "${rendered_file}")"
image_ref="$(awk '$1 == "image:" { gsub(/"/, "", $2); print $2; exit }' "${rendered_file}")"
managed_secret="false"
if awk -v target="${secret_name}" '
  $1 == "kind:" && $2 == "Secret" { in_secret = 1; next }
  in_secret && $1 == "name:" && $2 == target { found = 1 }
  /^---$/ { in_secret = 0 }
  END { exit found ? 0 : 1 }
' "${rendered_file}"; then
  managed_secret="true"
fi

if ! kubectl --request-timeout=5s cluster-info >/dev/null 2>&1; then
  echo "Kubernetes cluster is unavailable; local lint/template checks passed." >&2
  exit 3
fi

if grep -q '^kind: ServiceMonitor$' "${rendered_file}" \
  && ! kubectl api-resources --api-group=monitoring.coreos.com -o name | grep -qx servicemonitors; then
  echo "ServiceMonitor is enabled but Prometheus Operator CRDs are unavailable." >&2
  exit 4
fi

if [[ "${apply}" != "true" ]]; then
  helm upgrade --install "${release}" deploy/helm/kubeoncall \
    --namespace "${namespace}" \
    "${values_args[@]}" \
    --dry-run=server \
    --debug >"${server_dry_run_file}"
  echo "Server-side Helm dry-run passed. Set KUBEONCALL_HELM_APPLY=true to deploy."
  exit 0
fi

kubectl get namespace "${namespace}" >/dev/null 2>&1 || kubectl create namespace "${namespace}"
if [[ -z "${secret_name}" ]]; then
  echo "Unable to resolve the configured Kubernetes Secret from the rendered Deployment." >&2
  exit 5
fi
if [[ "${managed_secret}" != "true" ]]; then
  kubectl -n "${namespace}" get secret "${secret_name}" >/dev/null
fi

if [[ "${KUBEONCALL_HELM_LOAD_LOCAL_IMAGE:-false}" == "true" ]]; then
  local_images=()
  while IFS= read -r local_image; do
    local_images+=("${local_image}")
  done < <(
    awk '$1 == "image:" { gsub(/"/, "", $2); print $2 }' "${rendered_file}" |
      sort -u |
      grep -E '^kubeoncall([:-]|-)'
  )
  if [[ "${#local_images[@]}" -eq 0 ]]; then
    echo "No local kubeoncall images were found in the rendered chart." >&2
    exit 5
  fi
  current_context="$(kubectl config current-context)"
  for local_image in "${local_images[@]}"; do
    docker image inspect "${local_image}" >/dev/null
    case "${current_context}" in
      minikube*)
        minikube image load --overwrite=true "${local_image}"
        ;;
      kind-*)
        kind load docker-image --name "${current_context#kind-}" "${local_image}"
        ;;
      *)
        echo "Automatic local image loading supports only Minikube and Kind contexts." >&2
        exit 5
        ;;
    esac
  done
elif [[ "${image_ref}" != */* ]]; then
  echo "The chart uses a local-only image reference: ${image_ref}." >&2
  echo "Publish registry images or set KUBEONCALL_HELM_LOAD_LOCAL_IMAGE=true." >&2
  exit 5
fi

helm upgrade --install "${release}" deploy/helm/kubeoncall \
  --namespace "${namespace}" \
  "${values_args[@]}" \
  --wait \
  --timeout "${KUBEONCALL_HELM_TIMEOUT:-10m}"

kubectl -n "${namespace}" rollout status deployment/"${release}"-kubeoncall \
  --timeout "${KUBEONCALL_ROLLOUT_TIMEOUT:-5m}"
kubectl -n "${namespace}" get service "${release}"-kubeoncall

if grep -q "name: ${release}-kubeoncall-console" "${rendered_file}"; then
  kubectl -n "${namespace}" rollout status deployment/"${release}"-kubeoncall-console \
    --timeout "${KUBEONCALL_ROLLOUT_TIMEOUT:-5m}"
fi

if grep -q "name: ${release}-kubeoncall-grafana-dashboard" "${rendered_file}"; then
  kubectl -n "${namespace}" get configmap "${release}"-kubeoncall-grafana-dashboard
else
  echo "Grafana Dashboard is disabled; skipping ConfigMap verification."
fi

if grep -q '^  name: sandbox-controller$' "${rendered_file}"; then
  sandbox_namespace="$(
    awk '
      $1 == "name:" && $2 == "sandbox-controller" { found = 1 }
      found && $1 == "namespace:" { print $2; exit }
    ' "${rendered_file}"
  )"
  kubectl -n "${sandbox_namespace}" rollout status deployment/sandbox-controller \
    --timeout "${KUBEONCALL_ROLLOUT_TIMEOUT:-5m}"
fi

if grep -q '^kind: ServiceMonitor$' "${rendered_file}"; then
  kubectl -n "${namespace}" get servicemonitor "${release}"-kubeoncall
else
  echo "ServiceMonitor is disabled; skipping its runtime verification."
fi

if grep -q 'app.kubernetes.io/component: quickstart-mysql' "${rendered_file}"; then
  bootstrap_username="$(
    awk '
      $1 == "-" && $2 == "name:" && $3 == "KUBEONCALL_BOOTSTRAP_ADMIN_USERNAME" { found = 1; next }
      found && $1 == "value:" { gsub(/"/, "", $2); print $2; exit }
    ' "${rendered_file}"
  )"
  bootstrap_password_key="${KUBEONCALL_BOOTSTRAP_ADMIN_PASSWORD_KEY:-bootstrap-admin-password}"
  bootstrap_password="$(
    kubectl -n "${namespace}" get secret "${secret_name}" \
      -o "go-template={{ index .data \"${bootstrap_password_key}\" | base64decode }}"
  )"
  smoke_port="${KUBEONCALL_HELM_SMOKE_PORT:-18080}"
  kubectl -n "${namespace}" port-forward "service/${release}-kubeoncall" \
    "${smoke_port}:8080" >"${temporary_directory}/port-forward.log" 2>&1 &
  port_forward_pid="$!"
  for _ in $(seq 1 30); do
    if curl --fail --silent "http://127.0.0.1:${smoke_port}/actuator/health/readiness" >/dev/null; then
      break
    fi
    sleep 1
  done
  login_payload="{\"username\":\"${bootstrap_username}\",\"password\":\"${bootstrap_password}\"}"
  login_status="$(
    curl --silent --show-error \
      --output "${temporary_directory}/login-response.json" \
      --write-out '%{http_code}' \
      --cookie-jar "${temporary_directory}/cookies.txt" \
      --header 'Content-Type: application/json' \
      --data "${login_payload}" \
      "http://127.0.0.1:${smoke_port}/api/v1/auth/login"
  )"
  if [[ "${login_status}" != "200" ]] \
    || ! grep -q '"authenticated":true' "${temporary_directory}/login-response.json" \
    || ! grep -q 'KOC_SESSION' "${temporary_directory}/cookies.txt"; then
    echo "Quickstart bootstrap-admin browser login smoke failed." >&2
    exit 6
  fi
  echo "Quickstart bootstrap-admin login smoke passed for ${bootstrap_username}."
fi
