---
id: node-notready-triage
name: Node NotReady Triage
version: v1
description: Diagnose Kubernetes NodeNotReady and distinguish node root cause from derived pod noise.
triggers: [nodenotready, node not ready, kubelet, node pressure]
resourceTypes: [node]
maxRisk: MEDIUM
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
  - alertmanager.sendAlertEvent
---
# Node NotReady Triage

1. Verify Ready conditions, leases, kubelet status, pressure conditions, and recent node events.
2. Correlate pod failures on the same node and treat them as derived symptoms until disproved.
3. Check control-plane reachability, runtime health, disk, memory, and network state.
4. Require approval before cordon, drain, restart, or eviction.
