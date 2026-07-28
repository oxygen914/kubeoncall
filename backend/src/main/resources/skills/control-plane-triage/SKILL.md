---
id: control-plane-triage
name: Kubernetes Control Plane Triage
version: v2
description: Diagnose API server and control-plane availability from live health, latency, quorum, and dependency evidence.
triggers: [api server unavailable, apiserver down, scheduler down, controller manager down, etcd latency, control plane]
resourceTypes: [cluster, node, pod]
alertNames: [KubeApiServerDownP0]
metricNames: [kube.controlplane.apiserver_up]
runbookIds: [runbook-control-plane-apiserver]
categories: [control-plane]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, control-plane, apiserver, scheduler, etcd]
maxRisk: LOW
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.getPods
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.instantQuery
  - prometheus.rangeQuery
  - alertmanager.listAlerts
---
# Kubernetes Control Plane Triage

## Diagnostic objective

Separate API server loss from etcd, certificate, network, scheduler, controller, or monitoring failures without changing control-plane state.

## Required live evidence

Confirm API reachability, error/latency trends, component readiness, leader election, etcd quorum, node heartbeats, and the alert window. Verify an independent signal before declaring an outage.

## Decision branches

- API unreachable with etcd healthy: inspect API server, certificates, load balancer, and network evidence.
- Etcd quorum or latency degraded: treat API and controller symptoms as derived until storage health is restored.
- Only one monitor cannot reach the API: investigate the monitoring path before escalating a cluster outage.
- Scheduler/controller unhealthy with API available: verify leaders, work queues, and recent changes.

## Safe next step

Use the runbook and escalate with timestamps, affected components, and current redundancy.

## Prohibited actions

Do not restart components, rotate certificates, modify manifests, or alter etcd membership from this diagnostic Skill.

## Recovery and escalation

Require stable API success, normal latency, healthy quorum and leaders, and recovered node heartbeats across two observation windows. Escalate immediately for quorum loss, all API endpoints down, or certificate failure.
