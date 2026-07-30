---
id: pod-oom-triage
name: Pod OOMKilled Triage
version: v1
description: Diagnose container OOMKilled events and distinguish cgroup limit, leak, traffic, and node-memory causes.
triggers: [oomkilled, pod oom, container out of memory, cgroup oom, memory limit exceeded]
resourceTypes: [pod]
alertNames: [PodOOMKilledP1]
metricNames: [kube.pod.oom_killed]
runbookIds: [runbook-pod-oom]
categories: [k8s-pod]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, pod, container, oom, memory]
maxRisk: LOW
toolWhitelist:
  - knowledge.searchSop
  - kubernetes.describeResource
  - kubernetes.getPods
  - kubernetes.describeWorkload
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
# Pod OOMKilled Triage

## Diagnostic objective

Distinguish a container limit breach from memory leak, traffic growth, oversized work, and node-wide memory pressure.

## Required live evidence

Confirm termination reason, exit code, restarts, requests/limits, QoS, node MemoryPressure, events, previous logs, working set/RSS, traffic or queue, GC/heap signals, and release timing. Compare healthy replicas and node headroom.

## Decision branches

- Usage reaches the container limit while node memory is healthy: compare stable working set and configured limit.
- Memory grows after a release: preserve trend and diagnostic artifacts; treat leak as a hypothesis until verified.
- Usage spikes with traffic or large work: correlate per-instance load and queue behavior.
- Kernel or node OOM affects several workloads: hand off to `node-memory-pressure-triage`.

## Safe next step

Record the memory timeline and capacity evidence; propose any action separately.

## Prohibited actions

Do not remove limits, restart or scale workloads, save sensitive heap dumps, or patch live Pods from this Skill.

## Recovery and escalation

Require stable restarts, bounded memory, sufficient Ready replicas, normal errors, and no node MemoryPressure. Escalate for repeated multi-replica OOM, node OOM, or suspected sensitive-data exposure.
