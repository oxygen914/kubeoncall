#!/usr/bin/env bash
# KubeOnCall immutable Kubernetes Secret staging (WBS-12 §7).
#
# Creates a new Secret revision from a protected env file. It never prints Secret values, overwrites
# an existing revision or switches a running Helm release. After review, update
# secrets.existingSecret in a values override and perform the normal Helm upgrade/rollback flow.
#
# Usage:
#   KUBEONCALL_SECRET_FILE=/secure/kubeoncall-secrets.env \
#     KUBEONCALL_ROTATE_APPLY=true ./scripts/rotate-kubernetes-secret.sh kubeoncall kubeoncall-secrets-20260723
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <namespace> <new-secret-name>" >&2
  exit 2
fi
NAMESPACE="$1"
NEW_SECRET_NAME="$2"
SECRET_FILE="${KUBEONCALL_SECRET_FILE:?KUBEONCALL_SECRET_FILE is required}"

if [ "${KUBEONCALL_ROTATE_APPLY:-false}" != "true" ]; then
  echo "[secret-rotate] dry run only. Set KUBEONCALL_ROTATE_APPLY=true to create ${NEW_SECRET_NAME}." >&2
  exit 2
fi
if [ ! -r "${SECRET_FILE}" ]; then
  echo "[secret-rotate] protected env file is unreadable: ${SECRET_FILE}" >&2
  exit 2
fi
if kubectl -n "${NAMESPACE}" get secret "${NEW_SECRET_NAME}" >/dev/null 2>&1; then
  echo "[secret-rotate] REFUSING: immutable revision already exists: ${NEW_SECRET_NAME}" >&2
  exit 2
fi

umask 077
kubectl -n "${NAMESPACE}" create secret generic "${NEW_SECRET_NAME}" \
  --from-env-file="${SECRET_FILE}" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[secret-rotate] staged ${NEW_SECRET_NAME}; no workload has been switched."
echo "[secret-rotate] next: helm upgrade ... --set secrets.existingSecret=${NEW_SECRET_NAME}"
echo "[secret-rotate] rollback: helm rollback <release> <previous-revision>, then delete this unused revision after retention."
