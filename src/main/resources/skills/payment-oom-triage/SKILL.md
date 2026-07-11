---
id: payment-oom-triage
name: Payment OOM Triage
version: v1
description: Triage payment-service OOMKilled or container out-of-memory incidents with safe read-only checks first.
triggers: [oom, oomkilled, out of memory, memory pressure, payment]
services: [payment-service, payment]
resourceTypes: [pod, deployment]
maxRisk: MEDIUM
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
  - alertmanager.sendAlertEvent
---
# Payment OOM Triage

1. Describe the affected pod or deployment and verify restart count, last state, limits, requests, and node placement.
2. Query recent working set, RSS, OOMKilled events, and restart trends.
3. Compare current limits with peak usage and deployment timing.
4. Require approval before scaling, restart, or configuration changes.

Known pitfall: previous incidents are hints only. Verify current state before acting.
