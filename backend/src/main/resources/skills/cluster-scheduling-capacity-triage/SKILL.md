---
id: cluster-scheduling-capacity-triage
name: Cluster Scheduling Capacity Triage
version: v1
description: Diagnose Pending Pods and FailedScheduling from allocatable capacity, requests, quota, taints, affinity, and topology evidence.
triggers: [pending pods, failedscheduling, unschedulable pods, insufficient cpu, insufficient memory, cluster capacity]
resourceTypes: [cluster]
alertNames: [ClusterPendingPodsP1]
metricNames: [kube.cluster.pending_pods]
runbookIds: [runbook-cluster-capacity]
categories: [capacity]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, scheduler, pending, capacity, quota]
maxRisk: LOW
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.getPods
  - kubernetes.describeWorkload
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.instantQuery
  - prometheus.rangeQuery
  - alertmanager.listAlerts
---
# Cluster Scheduling Capacity Triage

## Diagnostic objective

Explain cluster-level Pending Pods by separating real capacity shortage from quota, taint, affinity, topology, volume, and scheduler constraints.

## Required live evidence

Collect Pending Pod count and age, FailedScheduling reasons, requests and allocatable capacity by node, quota, taints/tolerations, affinity/topology, pod density, volume binding, autoscaler state, and recent changes.

## Decision branches

- Insufficient CPU or memory spans nodes: verify requests and usable headroom before declaring shortage.
- Quota or limit range rejects one namespace: isolate it from cluster-wide capacity.
- Taint, affinity, topology, or volume rules block placement: identify the exact constraint and owner.
- Pods are Pending because a workload is failing after scheduling: hand off to a Pod or Deployment Skill.

## Safe next step

Use the runbook to rank blockers; propose capacity or scheduling changes as approved work.

## Prohibited actions

Do not add nodes, relax quota, remove taints, rewrite affinity, evict workloads, or disable the scheduler from this Skill.

## Recovery and escalation

Require Pending backlog and scheduling failures to drain while headroom remains stable. Escalate for critical unschedulable workloads, exhausted failure domains, or control-plane scheduling degradation.
