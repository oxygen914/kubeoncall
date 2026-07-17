---
id: database-latency-triage
name: Database Latency Triage
version: v1
description: Diagnose database latency, connection saturation, lock contention, and dependency pressure without modifying data.
triggers: [database latency, slow query, connection pool, lock wait, deadlock]
services: [mysql, postgres, database]
resourceTypes: [service, pod]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [database, latency, connection, lock, dependency]
maxRisk: LOW
toolWhitelist:
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
---
# Database Latency Triage

1. Confirm application latency, error rate, pool utilization, active connections, and timeout trends.
2. Correlate slow requests with lock waits, long transactions, replication lag, and recent schema or configuration changes.
3. Separate database saturation from application retry storms and downstream network failures.
4. Never run cleanup, kill sessions, alter schema, or change database configuration without explicit approval.
