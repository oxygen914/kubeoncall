---
id: node-memory-pressure-triage
name: Node Memory Pressure Triage
version: v1
description: Diagnose node-wide memory pressure and kernel OOM while separating cache, workload, limit, swap, and capacity causes.
triggers: [node memory low, host memory pressure, node memorypressure, kernel oom, available memory low]
resourceTypes: [node]
alertNames: [HostMemoryPressureP1, HostMemoryPressureP0, NodeMemoryLow]
metricNames: [host.memory.available_percent, node.memory.available_percent]
runbookIds: [runbook-host-memory-pressure]
categories: [host, node-monitoring]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, node, memory, oom, pressure]
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
# Node Memory Pressure Triage

## Diagnostic objective

Distinguish node-wide anonymous memory, cache, swap, kernel OOM, workload growth, and capacity pressure from a single container limit breach.

## Required live evidence

Confirm available memory, working set, cache, swap, reclaim and OOM trends, MemoryPressure, evictions, top workloads, requests/limits, pool baselines, traffic, and releases. Preserve kernel OOM identity and time.

## Decision branches

- One container is OOMKilled while node headroom is healthy: hand off to `pod-oom-triage`.
- Several workloads grow and available memory falls: evaluate node overcommit and capacity.
- Cache is high but available and reclaim remain healthy: do not classify cache alone as pressure.
- Kernel OOM or evictions span workloads: treat node capacity and workload placement as primary evidence.

## Safe next step

Document memory composition, affected workloads, and headroom before proposing governed changes.

## Prohibited actions

Do not drop caches, disable limits, change swap or kernel settings, evict Pods, drain, or reboot nodes from this Skill.

## Recovery and escalation

Require stable available memory, no new OOM or evictions, cleared MemoryPressure, and healthy workloads across two windows. Escalate for critical workload kills, repeated kernel OOM, or multiple affected nodes.
