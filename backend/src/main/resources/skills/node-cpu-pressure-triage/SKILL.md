---
id: node-cpu-pressure-triage
name: Node CPU Pressure Triage
version: v1
description: Diagnose sustained node CPU pressure by separating workload demand, system time, I/O wait, steal, and monitoring error.
triggers: [node cpu high, host cpu high, cpu pressure, high load average, cpu iowait, cpu steal]
resourceTypes: [node]
alertNames: [HostHighCpuUsageP1, HostHighCpuUsageP0, NodeCPUHigh]
metricNames: [host.cpu.usage_percent, node.cpu.usage_percent]
runbookIds: [runbook-host-cpu-high]
categories: [host, node-monitoring]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, node, cpu, load, iowait]
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
# Node CPU Pressure Triage

## Diagnostic objective

Distinguish workload CPU demand from system overhead, I/O wait, steal time, retry amplification, and monitor error.

## Required live evidence

Confirm duration, affected nodes, core count, user/system/iowait/steal distribution, load, throttling, top workload trends, traffic and error rates, same-pool baselines, node conditions, and recent releases or jobs.

## Decision branches

- User CPU and one workload rise together: correlate demand, limits, throttling, and release timing.
- System CPU rises: inspect runtime, networking, interrupts, and kernel evidence.
- I/O wait leads load: hand off to disk or storage investigation.
- Steal rises or only one data source reports high CPU: verify infrastructure or collection-path evidence.

## Safe next step

Use the runbook to identify the dominant CPU mode and owner, then propose scale, traffic, scheduling, or process actions separately.

## Prohibited actions

Do not kill processes, restart or scale workloads, drain or reboot nodes, change limits, or lower alert thresholds from this Skill.

## Recovery and escalation

Require CPU modes, load, throttling, latency, and errors to stay normal across two windows. Escalate for all nodes in a pool, critical latency, or sustained lack of headroom.
