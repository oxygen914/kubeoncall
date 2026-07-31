---
id: node-runtime-pressure-triage
name: Node Runtime Pressure Triage
version: v1
description: Diagnose node clock drift and file descriptor pressure as operating-system runtime health failures without changing host state.
triggers: [node clock offset, time drift, ntp unsynchronized, file descriptor pressure, filefd exhaustion, too many open files]
resourceTypes: [node]
alertNames: [NodeClockOffsetHigh, NodeFileDescriptorPressure]
metricNames: [node.time.clock_offset_seconds, node.file_descriptor.usage_percent]
runbookIds: [runbook-time-sync, runbook-host-file-descriptor-pressure]
categories: [node-time, node-runtime]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, node, time, ntp, file-descriptor, runtime]
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
  - changes.getRecentChanges
  - alertmanager.listAlerts
---
# Node Runtime Pressure Triage

## Diagnostic objective

Separate clock synchronization failure and descriptor exhaustion from monitoring error, transient load, process leaks, and host-wide runtime degradation.

## Required live evidence

For clock drift, collect offset trend, synchronization state, source reachability, recent suspend/reboot, and peer-node offsets. For descriptors, collect allocated/max trend, per-process descriptor growth, socket/file classes, workload placement, error logs, and recent releases. Always confirm the alert with an independent current signal.

## Decision branches

- Clock offset rises while peers remain synchronized: inspect the node time service, upstream reachability, virtualization clock, and recent reboot history.
- Descriptor use grows with one process and open-file errors: identify the leaking workload and correlate growth with traffic or a release.
- Metric is high but resource state and peer signals disagree: classify as monitoring uncertainty and keep the conclusion unverified.

## Stop conditions

Do not recommend a restart, process kill, time step, limit change, or Pod eviction automatically. Stop at an evidence-backed cause, affected scope, confidence, and the bound versioned runbook.
