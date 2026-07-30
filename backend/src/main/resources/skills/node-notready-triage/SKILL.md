---
id: node-notready-triage
name: Node NotReady Triage
version: v3
description: Diagnose NodeNotReady and node-exporter loss while separating node root cause from derived Pod alerts.
triggers: [nodenotready, node not ready, node down, kubelet unavailable, node lease expired]
resourceTypes: [node]
alertNames: [KubeNodeNotReadyP0, NodeDown, NodeNotReady]
metricNames: [kube.node.ready, prometheus.up]
runbookIds: [runbook-node-notready]
categories: [k8s-node, node-monitoring]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, node, notready, kubelet, runtime]
maxRisk: LOW
toolWhitelist:
  - knowledge.searchSop
  - kubernetes.describeResource
  - kubernetes.getPods
  - kubernetes.queryLogs
  - kubernetes.queryEvents
  - kubernetes.queryPodLogs
  - kubernetes.queryMetricsContext
  - prometheus.queryRange
  - prometheus.instantQuery
  - prometheus.rangeQuery
  - alerts.getActiveAlerts
  - alertmanager.listAlerts
---
# Node NotReady Triage

## Diagnostic objective

Determine whether the node, kubelet, runtime, network path, or monitoring target is unavailable and identify derived workload impact.

## Required live evidence

Confirm Ready condition and transition time, lease freshness, kubelet and runtime status, pressure conditions, node events, control-plane reachability, exporter health, and affected Pods. Compare with another node and monitoring path.

## Decision branches

- Ready is false or unknown and lease is stale: prioritize node or kubelet reachability.
- Node is Ready but exporter is down: treat NodeDown as a monitoring-path failure until proven otherwise.
- Disk, memory, or PID pressure is present: hand off to the corresponding node resource Skill.
- Many Pod alerts share the node: treat them as derived symptoms unless independent Pod evidence exists.

## Safe next step

Use the runbook to capture the node timeline, failure domain, redundancy, and workload impact before proposing a governed node action.

## Prohibited actions

Do not cordon, drain, evict, restart kubelet, reboot, or modify node configuration from this Skill.

## Recovery and escalation

Require stable Ready and lease signals, healthy runtime and monitoring, and recovered workloads across two windows. Escalate immediately for multiple nodes, control-plane reachability loss, or critical single-node workloads.
