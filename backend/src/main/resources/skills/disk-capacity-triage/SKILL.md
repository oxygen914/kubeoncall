---
id: disk-capacity-triage
name: Disk Capacity Triage
version: v3
description: Diagnose node filesystem and inode pressure from usage, growth, mount, and workload evidence without deleting data.
triggers: [disk full, disk usage, inode usage, filesystem pressure, read only filesystem]
resourceTypes: [node]
alertNames: [HostDiskUsageP1, HostDiskUsageP0, HostInodeUsageP1, HostInodeUsageP0, NodeDiskHigh, NodeInodeHigh, NodeFilesystemReadOnly]
metricNames: [host.disk.usage_percent, host.inode.usage_percent, node.filesystem.usage_percent, node.filesystem.inode_usage_percent, node.filesystem.readonly]
runbookIds: [runbook-host-disk-usage, runbook-host-inode-usage]
categories: [host, node-monitoring]
applicableTasks: [QUERY_LOGS, QUERY_METRICS]
tags: [kubernetes, node, disk, filesystem, inode]
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
# Disk Capacity Triage

## Diagnostic objective

Distinguish byte capacity, inode exhaustion, read-only remount, persistent-volume, and growth-rate failures on the affected node.

## Required live evidence

Confirm mount point, filesystem type, byte and inode usage, available capacity, growth rate, read-only state, affected workloads, and recent image or log growth. Exclude pseudo filesystems and compare against another node.

## Decision branches

- Bytes high but inodes normal: identify the growing path or volume owner without deleting files.
- Inodes high but bytes normal: inspect small-file creation and rotation behavior.
- Filesystem read-only: collect storage and kernel evidence and treat cleanup as unsafe.
- Pressure is isolated to a persistent volume: hand off to the storage owner with volume identity and workload impact.

## Safe next step

Use the metric-specific runbook and document the mount, owner, growth rate, and remaining time before proposing capacity or cleanup work.

## Prohibited actions

Do not delete files, prune images, evict Pods, remount filesystems, or resize volumes from this Skill.

## Recovery and escalation

Require usage and inode trends below policy thresholds, writable state, and stable workloads across two windows. Escalate for read-only remount, critical-volume exhaustion, or rapidly shrinking headroom.
