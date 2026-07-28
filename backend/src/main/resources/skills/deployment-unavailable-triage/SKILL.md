---
id: deployment-unavailable-triage
name: Deployment Unavailable Triage
version: v2
description: Diagnose unavailable Deployment replicas and rollout stalls without treating every Pod restart as a Deployment failure.
triggers: [deployment unavailable, unavailable replicas, rollout stuck, rollout stalled, progress deadline exceeded]
resourceTypes: [deployment]
alertNames: [DeploymentUnavailableP1]
metricNames: [kube.deployment.unavailable_replicas]
runbookIds: [runbook-deployment-unavailable]
categories: [workload]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, deployment, rollout, availability]
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
# Deployment Unavailable Triage

## Diagnostic objective

Explain why desired Deployment capacity is unavailable by separating rollout, scheduling, image, probe, and dependency evidence.

## Required live evidence

Compare desired, updated, available and ready replicas; rollout conditions; ReplicaSet revisions; Pod events; probe results; image digest; and the release timeline. Inspect failing Pod logs only after identifying the affected revision.

## Decision branches

- Updated replicas stall: inspect scheduling, image pull, quota, and admission events.
- Pods run but never become Ready: verify probe failures and dependency reachability.
- Old and new revisions overlap: check rollout strategy, disruption budget, and capacity.
- A Pod has CrashLoopBackOff or OOMKilled: hand off to the specific Pod Skill instead of treating it as the primary Deployment cause.

## Safe next step

Use the runbook to assemble revision-level evidence and propose a separately governed rollback, scale, or configuration task.

## Prohibited actions

Do not restart, scale, roll back, patch, or delete Pods from this diagnostic Skill.

## Recovery and escalation

Require desired available replicas, completed rollout conditions, stable readiness, and recovered service indicators. Escalate when no healthy revision exists or the rollout affects a critical service.
