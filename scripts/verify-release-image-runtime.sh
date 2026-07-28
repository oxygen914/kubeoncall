#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backend_image="${KUBEONCALL_BACKEND_IMAGE:-kubeoncall-backend:packaging-test}"
console_image="${KUBEONCALL_CONSOLE_IMAGE:-kubeoncall-console:packaging-test}"
controller_image="${KUBEONCALL_SANDBOX_CONTROLLER_IMAGE:-kubeoncall-sandbox-controller:packaging-test}"
python_image="${KUBEONCALL_SANDBOX_PYTHON_IMAGE:-kubeoncall-sandbox-generated-python:packaging-test}"
shell_image="${KUBEONCALL_SANDBOX_SHELL_IMAGE:-kubeoncall-sandbox-generated-posix-shell:packaging-test}"
manifest_image="${KUBEONCALL_SANDBOX_MANIFEST_IMAGE:-kubeoncall-sandbox-manifest-validation:packaging-test}"

fixture_container="kubeoncall-packaging-fixtures-$$"
fixture_network="kubeoncall-packaging-$$"
cleanup_runtime_smoke() {
  docker rm --force "${fixture_container}" >/dev/null 2>&1 || true
  docker network rm "${fixture_network}" >/dev/null 2>&1 || true
}
trap cleanup_runtime_smoke EXIT

for image in \
  "${backend_image}" \
  "${console_image}" \
  "${controller_image}" \
  "${python_image}" \
  "${shell_image}" \
  "${manifest_image}"; do
  image_user="$(docker image inspect --format '{{.Config.User}}' "${image}")"
  if [[ -z "${image_user}" || "${image_user}" == "0" || "${image_user}" == "root" || "${image_user}" == 0:* ]]; then
    echo "Release image must declare a non-root user: ${image}" >&2
    exit 6
  fi
done

docker network create "${fixture_network}" >/dev/null
docker run --detach --rm \
  --name "${fixture_container}" \
  --network "${fixture_network}" \
  --read-only \
  --tmpfs /etc/nginx/conf.d:rw,uid=101,gid=101,mode=0755 \
  --tmpfs /var/cache/nginx:rw,uid=101,gid=101,mode=0755 \
  --tmpfs /var/run:rw,uid=101,gid=101,mode=0755 \
  --tmpfs /tmp:rw,uid=101,gid=101,mode=1777 \
  --volume "${repository_root}/sandbox-runtimes/testdata:/usr/share/nginx/html:ro" \
  -e KUBEONCALL_BACKEND_HOST="${fixture_container}" \
  -e KUBEONCALL_BACKEND_PORT=8080 \
  "${console_image}" >/dev/null
fixture_ready=false
for _ in {1..20}; do
  if docker exec "${fixture_container}" wget -q -O /dev/null \
    http://127.0.0.1:8080/generated-python.json; then
    fixture_ready=true
    break
  fi
  sleep 0.25
done
if [[ "${fixture_ready}" != "true" ]]; then
  echo "Runtime fixture server did not become ready" >&2
  exit 7
fi

python_result="$(docker run --rm \
  --network "${fixture_network}" \
  --read-only \
  --tmpfs /sandbox:rw,uid=65532,gid=65532,mode=0700 \
  -e SANDBOX_INPUT_ARTIFACT_URI="http://${fixture_container}:8080/generated-python.json" \
  "${python_image}")"
grep -q '"status":"SUCCEEDED"' <<<"${python_result}"
grep -q 'PYTHON_SMOKE_OK' <<<"${python_result}"

shell_result="$(docker run --rm \
  --network "${fixture_network}" \
  --read-only \
  --tmpfs /sandbox:rw,uid=65532,gid=65532,mode=0700 \
  -e SANDBOX_INPUT_ARTIFACT_URI="http://${fixture_container}:8080/generated-posix-shell.json" \
  "${shell_image}")"
grep -q '"status":"SUCCEEDED"' <<<"${shell_result}"
grep -q 'generated POSIX shell runtime smoke passed' <<<"${shell_result}"

manifest_result="$(docker run --rm \
  --network "${fixture_network}" \
  --read-only \
  --tmpfs /sandbox:rw,uid=65532,gid=65532,mode=0700 \
  -e SANDBOX_INPUT_ARTIFACT_URI="http://${fixture_container}:8080/manifest-validation.json" \
  "${manifest_image}")"
grep -q '"valid":true' <<<"${manifest_result}"
grep -q '"rulesetVersion":"sandbox-manifest-rules-v1"' <<<"${manifest_result}"

echo "KubeOnCall release image runtime smoke passed."
