---
id: control-plane-triage
name: Kubernetes Control Plane Triage
version: v1
description: Diagnose API server, scheduler, controller manager, and etcd health while protecting control-plane availability.
triggers: [api server unavailable, scheduler down, controller manager, etcd latency, control plane]
resourceTypes: [cluster, node, pod]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, control-plane, apiserver, scheduler, etcd]
maxRisk: LOW
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
  - alertmanager.listAlerts
---
# Kubernetes Control Plane Triage

1. Verify API availability, request latency, error codes, leader election, etcd quorum, and component readiness.
2. Correlate symptoms across API server, scheduler, controller manager, etcd, and node heartbeats.
3. Check certificate validity, storage pressure, network reachability, and recent control-plane changes.
4. Require an approved maintenance procedure before restarting or changing any control-plane component.
