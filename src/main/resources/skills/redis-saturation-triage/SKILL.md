---
id: redis-saturation-triage
name: Redis Saturation Triage
version: v1
description: Diagnose Redis latency, memory pressure, blocked clients, eviction, and persistence stalls without destructive commands.
triggers: [redis latency, redis memory, blocked clients, eviction, redis timeout]
services: [redis]
resourceTypes: [service, pod]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [redis, cache, latency, memory, persistence]
maxRisk: LOW
toolWhitelist:
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
---
# Redis Saturation Triage

1. Check command latency, connected and blocked clients, memory fragmentation, evictions, hit rate, and persistence duration.
2. Correlate application retry volume, hot keys, large values, network latency, and recent configuration changes.
3. Distinguish capacity pressure from fork, AOF, RDB, replica, or client-side pool stalls.
4. Never flush keys, rewrite persistence, fail over, or change maxmemory policy without explicit approval.
