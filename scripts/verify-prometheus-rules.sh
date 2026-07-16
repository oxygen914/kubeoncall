#!/usr/bin/env sh
set -eu

image="${PROMETHEUS_IMAGE:-prom/prometheus:v2.53.1}"

docker run --rm \
  -v "$(pwd)/deploy/prometheus:/etc/prometheus:ro" \
  "$image" \
  promtool check rules /etc/prometheus/rules/kubeoncall.yml

docker run --rm \
  -v "$(pwd)/deploy/prometheus:/etc/prometheus:ro" \
  "$image" \
  promtool test rules /etc/prometheus/tests/kubeoncall.test.yml
