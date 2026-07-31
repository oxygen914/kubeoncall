#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
chart_directory="${repository_root}/deploy/helm/kubeoncall"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT

release_values="${temporary_directory}/release-values.yaml"
"${repository_root}/scripts/render-release-values.sh" \
  ghcr.io/oxygen914 \
  0.1.0-test \
  "${repository_root}/scripts/testdata/release-digests" \
  "${release_values}"

helm lint --strict "${chart_directory}"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  >"${temporary_directory}/default.yaml"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  --values "${chart_directory}/values-production.yaml" \
  >"${temporary_directory}/production.yaml"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  --values "${chart_directory}/values-production.yaml" \
  --values "${release_values}" \
  >"${temporary_directory}/production-sandbox.yaml"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  --values "${chart_directory}/values-production.yaml" \
  --show-only templates/deployment.yaml \
  >"${temporary_directory}/production-backend.yaml"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  --values "${chart_directory}/values-quickstart.yaml" \
  >"${temporary_directory}/quickstart.yaml"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  --values "${chart_directory}/values-quickstart.yaml" \
  --values "${release_values}" \
  >"${temporary_directory}/sandbox.yaml"
helm template kubeoncall "${chart_directory}" \
  --namespace kubeoncall \
  --set autoscaling.enabled=true \
  --set console.autoscaling.enabled=true \
  >"${temporary_directory}/autoscaling.yaml"

grep -q 'app.kubernetes.io/component: quickstart-mysql' "${temporary_directory}/quickstart.yaml"
grep -q 'app.kubernetes.io/component: backend' "${temporary_directory}/quickstart.yaml"
grep -q 'name: wait-for-mysql' "${temporary_directory}/quickstart.yaml"
grep -q 'value: "kubeoncall-kubeoncall"' "${temporary_directory}/quickstart.yaml"
grep -q 'value: "http://minio.kubeoncall.svc.cluster.local:9000"' "${temporary_directory}/quickstart.yaml"
grep -q 'org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration' "${temporary_directory}/quickstart.yaml"
grep -A1 'name: KUBEONCALL_AUTH_SESSION_COOKIE_SECURE' "${temporary_directory}/quickstart.yaml" |
  grep -q 'value: "false"'
grep -A1 'name: KUBEONCALL_BOOTSTRAP_ADMIN_ENABLED' "${temporary_directory}/quickstart.yaml" |
  grep -q 'value: "true"'
grep -A1 'name: KUBEONCALL_BOOTSTRAP_ADMIN_USERNAME' "${temporary_directory}/quickstart.yaml" |
  grep -q 'value: "admin"'
grep -A1 'name: KUBEONCALL_LEGACY_API_ENABLED' "${temporary_directory}/quickstart.yaml" |
  grep -q 'value: "false"'
if grep -q 'org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration' "${temporary_directory}/production-backend.yaml"; then
  echo "Production must not disable explicitly enabled Spring AI auto-configuration." >&2
  exit 5
fi
grep -A1 'name: KUBEONCALL_API_AUTH_ENABLED' "${temporary_directory}/production.yaml" |
  grep -q 'value: "false"'
grep -A5 'key: api-admin-token' "${temporary_directory}/production.yaml" |
  grep -q 'optional: true'
grep -q '^kind: PodDisruptionBudget$' "${temporary_directory}/production.yaml"
grep -q '^kind: NetworkPolicy$' "${temporary_directory}/production.yaml"
grep -q 'readOnlyRootFilesystem: true' "${temporary_directory}/production.yaml"
grep -q 'seccompProfile:' "${temporary_directory}/production.yaml"
grep -q 'topologySpreadConstraints:' "${temporary_directory}/production.yaml"
grep -q '^kind: HorizontalPodAutoscaler$' "${temporary_directory}/autoscaling.yaml"
if grep -Eq '^[[:space:]]+- path: /actuator' "${temporary_directory}/production.yaml"; then
  echo "Actuator must remain cluster-internal and cannot be routed by Ingress." >&2
  exit 5
fi
if grep -q '^kind: Namespace$' "${temporary_directory}/production-sandbox.yaml"; then
  echo "Production must not let the application release own the Sandbox namespace." >&2
  exit 5
fi
grep -q 'name: sandbox-controller' "${temporary_directory}/sandbox.yaml"
grep -q '^kind: Namespace$' "${temporary_directory}/sandbox.yaml"
grep -q 'SANDBOX_CONTROLLER_TOOL_CATALOG_PATH' "${temporary_directory}/sandbox.yaml"
grep -q 'SANDBOX_CONTROLLER_NAMESPACE' "${temporary_directory}/sandbox.yaml"
grep -q 'value: "http://sandbox-controller.kubeoncall-sandbox.svc.cluster.local:8088"' "${temporary_directory}/sandbox.yaml"
grep -q 'kubeoncall-sandbox-generated-python@sha256:' "${temporary_directory}/sandbox.yaml"
grep -q 'cidr: 0.0.0.0/0' "${temporary_directory}/sandbox.yaml"
if grep -Eq 'value: "[0-9.]+e[+-][0-9]+"' "${temporary_directory}/quickstart.yaml"; then
  echo "Application numeric environment values must not render in scientific notation." >&2
  exit 5
fi
if grep -Eq 'MaxBytes: [0-9.]+e[+-][0-9]+' "${temporary_directory}/sandbox.yaml"; then
  echo "Sandbox catalogue byte ceilings must render as integers." >&2
  exit 5
fi

if helm template invalid "${chart_directory}" --set replicaCount=0 \
  >"${temporary_directory}/invalid-values.yaml" 2>"${temporary_directory}/invalid-values.log"; then
  echo "values.schema.json must reject replicaCount=0." >&2
  exit 5
fi

while IFS= read -r dockerfile; do
  if awk '$1 == "FROM" && $2 !~ /@sha256:[0-9a-f]{64}$/ {exit 1}' "${dockerfile}"; then
    continue
  fi
  echo "Every Dockerfile base stage must be pinned by sha256: ${dockerfile}" >&2
  exit 5
done < <(find \
  "${repository_root}/backend" \
  "${repository_root}/frontend" \
  "${repository_root}/sandbox-controller" \
  "${repository_root}/sandbox-runtimes" \
  "${repository_root}/deploy/local" \
  -name 'Dockerfile*' -type f | sort)

if [[ "${KUBEONCALL_BUILD_RELEASE_IMAGES:-false}" == "true" ]]; then
  (
    cd "${repository_root}/backend"
    ./mvnw --batch-mode --no-transfer-progress -DskipTests package
    jar_path="$(find target -maxdepth 1 -name 'kubeoncall-*.jar' ! -name '*.original' -print -quit)"
    test -n "${jar_path}"
    cp "${jar_path}" target/kubeoncall.jar
  )
  docker build \
    -f "${repository_root}/deploy/local/Dockerfile.prebuilt" \
    -t kubeoncall-backend:packaging-test \
    "${repository_root}"
  docker build -t kubeoncall-console:packaging-test "${repository_root}/frontend"
  docker run --rm \
    -e KUBEONCALL_BACKEND_HOST=release-kubeoncall \
    -e KUBEONCALL_BACKEND_PORT=8080 \
    kubeoncall-console:packaging-test \
    sh -c '/docker-entrypoint.d/20-envsubst-on-templates.sh >/dev/null &&
      grep -q "proxy_pass http://release-kubeoncall:8080;" /etc/nginx/conf.d/default.conf'
  docker build -t kubeoncall-sandbox-controller:packaging-test "${repository_root}/sandbox-controller"
  docker build \
    -f "${repository_root}/sandbox-runtimes/generated-code/python/Dockerfile" \
    -t kubeoncall-sandbox-generated-python:packaging-test \
    "${repository_root}/sandbox-runtimes/generated-code"
  docker build \
    -f "${repository_root}/sandbox-runtimes/generated-code/posix-shell/Dockerfile" \
    -t kubeoncall-sandbox-generated-posix-shell:packaging-test \
    "${repository_root}/sandbox-runtimes/generated-code"
  docker build \
    -f "${repository_root}/sandbox-runtimes/manifest-validation/Dockerfile" \
    -t kubeoncall-sandbox-manifest-validation:packaging-test \
    "${repository_root}/sandbox-runtimes/manifest-validation"
  "${repository_root}/scripts/verify-release-image-runtime.sh"
fi

echo "KubeOnCall release packaging validation passed."
