---
id: payment-oom-triage
name: Payment OOM Triage
description: Triage payment-service OOMKilled or container out-of-memory incidents with safe read-only checks first.
triggers:
  - oom
  - oomkilled
  - out of memory
  - memory pressure
  - payment
services:
  - payment-service
  - payment
resourceTypes:
  - pod
  - deployment
maxRisk: MEDIUM
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
  - alertmanager.sendAlertEvent
---
# Payment OOM Triage

Prefer read-only diagnosis before any change:

1. Describe the affected pod/deployment and check restart count, last state, limits, requests, and node placement.
2. Query recent memory working set, RSS, OOMKilled events, and container restart trend for the last 30 minutes.
3. Compare current limits with recent peak usage and recent release/deploy timing.
4. If a scaling, restart, or limit change is needed, require approval before execution.

Known pitfall: do not assume the previous OOM cause is still current. Verify live pod state and current memory metrics first.
