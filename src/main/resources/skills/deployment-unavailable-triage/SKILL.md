---
id: deployment-unavailable-triage
name: Deployment Unavailable Triage
version: v1
description: Diagnose unavailable replicas, rollout stalls, and CrashLoop propagation.
triggers: [deployment unavailable, unavailable replicas, rollout stuck, crashloop]
resourceTypes: [deployment, pod]
maxRisk: MEDIUM
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
  - alertmanager.sendAlertEvent
---
# Deployment Unavailable Triage

1. Compare desired, updated, available, and ready replicas and inspect rollout conditions.
2. Inspect failing pods, scheduling events, probes, image pulls, and recent revisions.
3. Correlate changes and upstream dependencies before proposing rollback or restart.
4. Scaling, rollback, patching, and restart require approval.
