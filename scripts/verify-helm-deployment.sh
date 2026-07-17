#!/usr/bin/env bash
set -euo pipefail

release="${KUBEONCALL_HELM_RELEASE:-kubeoncall}"
namespace="${KUBEONCALL_HELM_NAMESPACE:-kubeoncall}"
values_file="${KUBEONCALL_HELM_VALUES:-deploy/helm/kubeoncall/values-production.yaml}"
apply="${KUBEONCALL_HELM_APPLY:-false}"

helm lint deploy/helm/kubeoncall
helm template "${release}" deploy/helm/kubeoncall \
  --namespace "${namespace}" \
  --values "${values_file}" >/tmp/kubeoncall-helm-rendered.yaml

secret_name="$(awk '
  /secretKeyRef:/ { in_secret = 1; next }
  in_secret && $1 == "name:" { print $2; exit }
' /tmp/kubeoncall-helm-rendered.yaml)"
image_ref="$(awk '$1 == "image:" { gsub(/"/, "", $2); print $2; exit }' /tmp/kubeoncall-helm-rendered.yaml)"

if ! kubectl --request-timeout=5s cluster-info >/dev/null 2>&1; then
  echo "Kubernetes cluster is unavailable; local lint/template checks passed." >&2
  exit 3
fi

if grep -q '^kind: ServiceMonitor$' /tmp/kubeoncall-helm-rendered.yaml \
  && ! kubectl api-resources --api-group=monitoring.coreos.com -o name | grep -qx servicemonitors; then
  echo "ServiceMonitor is enabled but Prometheus Operator CRDs are unavailable." >&2
  exit 4
fi

if [[ "${apply}" != "true" ]]; then
  helm upgrade --install "${release}" deploy/helm/kubeoncall \
    --namespace "${namespace}" \
    --values "${values_file}" \
    --dry-run=server \
    --debug >/tmp/kubeoncall-helm-server-dry-run.txt
  echo "Server-side Helm dry-run passed. Set KUBEONCALL_HELM_APPLY=true to deploy."
  exit 0
fi

kubectl get namespace "${namespace}" >/dev/null 2>&1 || kubectl create namespace "${namespace}"
if [[ -z "${secret_name}" ]]; then
  echo "Unable to resolve the configured Kubernetes Secret from the rendered Deployment." >&2
  exit 5
fi
kubectl -n "${namespace}" get secret "${secret_name}" >/dev/null

if [[ "${image_ref}" == "kubeoncall:latest" ]]; then
  if [[ "${KUBEONCALL_HELM_LOAD_LOCAL_IMAGE:-false}" != "true" ]]; then
    echo "The chart still uses local image kubeoncall:latest." >&2
    echo "Publish a registry image in values, or set KUBEONCALL_HELM_LOAD_LOCAL_IMAGE=true for Minikube." >&2
    exit 5
  fi
  docker image inspect "${image_ref}" >/dev/null
  if [[ "$(kubectl config current-context)" != minikube* ]]; then
    echo "Automatic local image loading is supported only for a Minikube context." >&2
    exit 5
  fi
  minikube image load "${image_ref}"
fi

helm upgrade --install "${release}" deploy/helm/kubeoncall \
  --namespace "${namespace}" \
  --values "${values_file}" \
  --wait \
  --timeout "${KUBEONCALL_HELM_TIMEOUT:-10m}"

kubectl -n "${namespace}" rollout status deployment/"${release}"-kubeoncall \
  --timeout "${KUBEONCALL_ROLLOUT_TIMEOUT:-5m}"
kubectl -n "${namespace}" get configmap "${release}"-kubeoncall-grafana-dashboard
kubectl -n "${namespace}" get service "${release}"-kubeoncall

if grep -q '^kind: ServiceMonitor$' /tmp/kubeoncall-helm-rendered.yaml; then
  kubectl -n "${namespace}" get servicemonitor "${release}"-kubeoncall
else
  echo "ServiceMonitor is disabled; skipping its runtime verification."
fi
