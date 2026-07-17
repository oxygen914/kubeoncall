---
id: disk-capacity-triage
name: Disk Capacity Triage
version: v1
description: Diagnose disk and inode pressure without unsafe automatic deletion.
triggers: [disk full, disk usage, inode, filesystem, readonly]
resourceTypes: [node, pod]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, disk, filesystem, inode]
maxRisk: MEDIUM
toolWhitelist:
  - kubernetes.describeResource
  - kubernetes.queryLogs
  - kubernetes.queryMetricsContext
  - prometheus.rangeQuery
  - alertmanager.sendAlertEvent
---
# Disk Capacity Triage

1. Verify mount point, filesystem type, inode usage, growth rate, and read-only state.
2. Identify top consumers and recent growth without deleting files.
3. Check log rotation, image garbage collection, and persistent volume ownership.
4. Any cleanup, eviction, or volume change requires explicit approval.
