---
id: network-connectivity-triage
name: Network Connectivity Triage
version: v2
description: Diagnose DNS, TCP, Service endpoint, packet-loss, and conntrack symptoms using bounded read-only evidence.
triggers: [connection timeout, connection refused, dns failure, packet loss, network unreachable, service endpoint missing]
resourceTypes: [service, pod, node]
alertNames: [HostConntrackPressureP1]
metricNames: [host.network.conntrack_usage_percent]
runbookIds: [runbook-network-conntrack]
categories: [network]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [network, dns, tcp, endpoint, conntrack]
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
# Network Connectivity Triage

## Diagnostic objective

Locate failure at DNS resolution, endpoint selection, connection establishment, TLS, or packet transport without changing network state.

## Required live evidence

Identify source, destination, port, protocol, affected Pods and nodes, first failure time, DNS result, Service selectors, ready endpoints, connection outcome, retransmits, packet loss, and conntrack utilization.

## Decision branches

- DNS resolution fails: compare resolver reachability, response code, search path, and affected namespaces.
- Service resolves but has no ready endpoints: inspect selectors and Pod readiness; treat it as workload evidence.
- Connections time out or reset: compare node locality, policy evidence, retransmits, and destination health.
- Conntrack is high: confirm entries, limit, growth, and node concentration before assigning root cause.

## Safe next step

Use the relevant network or conntrack runbook and report the exact failed hop with independent evidence.

## Prohibited actions

Do not edit NetworkPolicy, flush conntrack, restart networking, change DNS, or bypass TLS from this Skill.

## Recovery and escalation

Require stable resolution, ready endpoints, successful connections, and normal loss/retransmit indicators. Escalate for multi-node impact, control-plane isolation, or sustained conntrack exhaustion.
