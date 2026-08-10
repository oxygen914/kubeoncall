#!/usr/bin/env bash
set -euo pipefail

umask 077

namespace="${KUBEONCALL_NAMESPACE:-kubeoncall}"
backup_root="${KUBEONCALL_BACKUP_ROOT:-/home/jsa/kubeoncall-backups}"
retention_days="${KUBEONCALL_BACKUP_RETENTION_DAYS:-14}"
release="${KUBEONCALL_HELM_RELEASE:-kubeoncall}"
mc_image="minio/mc:RELEASE.2024-07-31T15-58-33Z"

mkdir -p "$backup_root"
exec 9>"$backup_root/.backup.lock"
if ! flock -n 9; then
  printf 'backup already running\n' >&2
  exit 0
fi

available_kib=$(df -Pk "$backup_root" | awk 'NR==2 {print $4}')
if [ "${available_kib:-0}" -lt 10485760 ]; then
  printf 'backup aborted: less than 10 GiB free\n' >&2
  exit 1
fi

stamp=$(date +%Y%m%d-%H%M%S)
backup_dir="$backup_root/daily-$stamp"
credential_file=$(mktemp /tmp/koc-backup-minio.XXXXXX)
portforward_log=$(mktemp /tmp/koc-backup-pf.XXXXXX)
portforward_pid=''
original_replicas=$(kubectl -n "$namespace" get deployment kubeoncall-kubeoncall -o jsonpath='{.spec.replicas}')

cleanup() {
  if [ -n "$portforward_pid" ]; then
    kill "$portforward_pid" >/dev/null 2>&1 || true
  fi
  rm -f "$credential_file" "$portforward_log"
  kubectl -n "$namespace" scale deployment/kubeoncall-kubeoncall --replicas="$original_replicas" >/dev/null 2>&1 || true
  kubectl -n "$namespace" rollout status deployment/kubeoncall-kubeoncall --timeout=300s >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$backup_dir/minio-objects"
chmod 700 "$backup_dir"

helm get values "$release" -n "$namespace" -o yaml >"$backup_dir/helm-values.yaml"
helm get manifest "$release" -n "$namespace" >"$backup_dir/helm-manifest.yaml"
kubectl -n "$namespace" get deploy,svc,endpoints,pvc -o yaml >"$backup_dir/runtime-state.yaml"

kubectl -n "$namespace" scale deployment/kubeoncall-kubeoncall --replicas=0 >/dev/null
kubectl -n "$namespace" wait --for=delete pod -l app.kubernetes.io/component=backend --timeout=180s >/dev/null 2>&1 || true

mysql_pod=$(kubectl -n "$namespace" get pod -l app.kubernetes.io/component=quickstart-mysql -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$namespace" exec "$mysql_pod" -- sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction --routines --events --databases kubeoncall' >"$backup_dir/mysql-kubeoncall.sql"

redis_pod=$(kubectl -n "$namespace" get pod -l app.kubernetes.io/component=quickstart-redis -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$namespace" exec "$redis_pod" -- redis-cli SAVE >/dev/null
kubectl -n "$namespace" exec "$redis_pod" -- sh -c 'tar czf - -C /data .' >"$backup_dir/redis-data.tgz"

elasticsearch_pod=$(kubectl -n "$namespace" get pod -l app.kubernetes.io/component=quickstart-elasticsearch -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$namespace" exec "$elasticsearch_pod" -- sh -c 'curl -fsS -X POST http://127.0.0.1:9200/_flush >/dev/null; tar czf - -C /usr/share/elasticsearch/data .' >"$backup_dir/elasticsearch-data.tgz"

: >"$credential_file"
chmod 600 "$credential_file"
printf 'MINIO_ROOT_USER=%s\n' "$(kubectl -n "$namespace" get secret kubeoncall-secrets -o jsonpath='{.data.minio-access-key}' | base64 -d)" >"$credential_file"
printf 'MINIO_ROOT_PASSWORD=%s\n' "$(kubectl -n "$namespace" get secret kubeoncall-secrets -o jsonpath='{.data.minio-secret-key}' | base64 -d)" >>"$credential_file"
kubectl -n "$namespace" port-forward --address=127.0.0.1 service/minio 39000:9000 >"$portforward_log" 2>&1 &
portforward_pid=$!
for attempt in $(seq 1 20); do
  curl -fsS --max-time 2 http://127.0.0.1:39000/minio/health/ready >/dev/null 2>&1 && break
  sleep 1
done
docker run --rm --network host --entrypoint /bin/sh --user "$(id -u):$(id -g)" --env-file "$credential_file" -e HOME=/tmp -v "$backup_dir/minio-objects:/backup" "$mc_image" -c 'mc alias set source http://127.0.0.1:39000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc mirror --quiet --overwrite source/kubeoncall-docs /backup/kubeoncall-docs'
tar czf "$backup_dir/minio-objects.tgz" -C "$backup_dir/minio-objects" .

sha256sum "$backup_dir"/helm-values.yaml "$backup_dir"/helm-manifest.yaml "$backup_dir"/runtime-state.yaml "$backup_dir"/mysql-kubeoncall.sql "$backup_dir"/redis-data.tgz "$backup_dir"/elasticsearch-data.tgz "$backup_dir"/minio-objects.tgz >"$backup_dir/SHA256SUMS"
gzip -t "$backup_dir/redis-data.tgz" "$backup_dir/elasticsearch-data.tgz" "$backup_dir/minio-objects.tgz"

cleanup
trap - EXIT

find "$backup_root" -mindepth 1 -maxdepth 1 -type d -name 'daily-*' -mtime "+$retention_days" -print0 | xargs -0r rm -rf --
printf 'backup complete: %s\n' "$backup_dir"
