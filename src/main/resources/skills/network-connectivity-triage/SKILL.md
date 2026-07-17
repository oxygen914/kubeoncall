---
id: network-connectivity-triage
name: Network Connectivity Triage
version: v1
description: Diagnose DNS, connection timeout, packet loss, and Kubernetes service reachability using read-only evidence.
triggers: [connection timeout, connection refused, dns failure, packet loss, network unreachable]
resourceTypes: [service, pod, node]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [network, dns, tcp, timeout, kubernetes]
maxRisk: LOW
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
---
# Network Connectivity Triage

1. Identify whether failure occurs at DNS resolution, connection establishment, TLS negotiation, or application response.
2. Compare affected pods, nodes, namespaces, endpoints, and dependency directions before assigning root cause.
3. Check service selectors, endpoint readiness, network policy evidence, packet loss, retransmits, and conntrack pressure.
4. Do not restart workloads, edit policies, or flush network state during diagnosis.
