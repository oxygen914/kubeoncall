---
id: database-latency-triage
name: Database Latency Triage
version: v2
description: Diagnose database latency, pool saturation, lock contention, and dependency pressure without modifying data.
triggers: [database latency, slow query, connection pool, lock wait, deadlock]
services: [mysql, postgres, database]
resourceTypes: [service, pod]
categories: [database, dependency]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [database, latency, connection, lock, dependency]
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
# Database Latency Triage

## Diagnostic objective

Distinguish database saturation from application pool, retry, network, and transaction behavior using current evidence.

## Required live evidence

Collect request latency and errors, pool wait/utilization, active connections, query latency, lock waits, long transactions, replication lag, and the change timeline. Compare an affected service with a healthy peer.

## Decision branches

- Pool wait rises while database latency is normal: inspect client limits, leaks, and retry concurrency.
- Query and lock latency rise together: identify the transaction pattern and owner without killing sessions.
- Replication lag rises: separate replica read pressure, network delay, and primary write saturation.
- Application retries lead the database spike: treat retry amplification as a contributing cause.

## Safe next step

Preserve query identifiers and timestamps, limit conclusions to verified signals, and use the dependency owner runbook for approved mitigation.

## Prohibited actions

Do not clean data, kill sessions, alter schema, fail over, or change database configuration from this Skill.

## Recovery and escalation

Require latency, errors, pool wait, locks, and lag to remain normal across two windows. Escalate for data integrity risk, primary unavailability, or sustained connection exhaustion.
