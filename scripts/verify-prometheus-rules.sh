#!/usr/bin/env sh
set -eu

image="${PROMETHEUS_IMAGE:-prom/prometheus:v2.53.1}"

docker run --rm --entrypoint promtool \
  -v "$(pwd)/deploy/prometheus:/etc/prometheus:ro" \
  "$image" \
  check rules /etc/prometheus/rules/kubeoncall.yml

docker run --rm --entrypoint promtool \
  -v "$(pwd)/deploy/prometheus:/etc/prometheus:ro" \
  "$image" \
  test rules /etc/prometheus/tests/kubeoncall.test.yml
