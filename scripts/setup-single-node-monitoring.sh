#!/usr/bin/env bash
set -euo pipefail

readonly profile="${MINIKUBE_PROFILE:-kubeoncall-monitoring}"
readonly kube_state_metrics_image="registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.19.0"
readonly repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly manifest="${repository_root}/deploy/monitoring/kube-state-metrics-single-node.yaml"
readonly compose_override="${repository_root}/docker-compose.monitoring.yml"

for command_name in docker minikube kubectl; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "missing required command: ${command_name}" >&2
    exit 1
  fi
done

if ! minikube status -p "${profile}" >/dev/null 2>&1; then
  minikube start -p "${profile}" --driver=docker --nodes=1 --cpus=2 --memory=3072
fi

if ! docker image inspect "${kube_state_metrics_image}" >/dev/null 2>&1; then
  docker pull "${kube_state_metrics_image}"
fi
if ! minikube image ls -p "${profile}" | grep -Fqx "${kube_state_metrics_image}"; then
  minikube image load "${kube_state_metrics_image}" -p "${profile}"
fi

minikube kubectl -p "${profile}" -- apply -f "${manifest}"
minikube kubectl -p "${profile}" -- rollout status deployment/kube-state-metrics \
  --namespace kube-system \
  --timeout=180s

docker compose \
  -f "${repository_root}/docker-compose.yml" \
  -f "${compose_override}" \
  up -d --no-deps prometheus

echo "single-node monitoring is ready"
echo "profile=${profile}"
echo "prometheus=http://127.0.0.1:9090"
echo "console=http://127.0.0.1:8081/monitoring"
