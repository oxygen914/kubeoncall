---
id: redis-saturation-triage
name: Redis Saturation Triage
version: v2
description: Diagnose Redis latency, memory pressure, blocked clients, eviction, and persistence stalls without destructive commands.
triggers: [redis latency, redis memory, blocked clients, eviction, redis timeout]
services: [redis]
resourceTypes: [service, pod]
categories: [cache, dependency]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [redis, cache, latency, memory, persistence]
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
# Redis Saturation Triage

## Diagnostic objective

Separate capacity pressure from client, hot-key, persistence, replication, and network behavior using current evidence.

## Required live evidence

Collect command latency, connected and blocked clients, memory use and fragmentation, evictions, hit rate, fork and persistence duration, replication state, retry volume, and the change timeline.

## Decision branches

- Memory and evictions rise: compare key growth, value size, policy, and workload demand.
- Blocked clients rise without server saturation: inspect slow commands and client concurrency.
- Latency aligns with fork, AOF, or RDB: preserve persistence timing before assigning network cause.
- One service drives retries or hot keys: treat client behavior as a contributing cause.

## Safe next step

Document the dominant signal, affected clients, and time window, then use the service runbook for an approved mitigation.

## Prohibited actions

Do not flush keys, delete data, rewrite persistence, fail over, or change maxmemory policy from this Skill.

## Recovery and escalation

Require normal latency, bounded memory, no new evictions, healthy persistence, and stable clients. Escalate for data-loss risk, primary unavailability, or sustained memory exhaustion.
