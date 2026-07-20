---
runbookId: runbook-time-sync
title: Cluster time synchronisation response SOP
category: infrastructure
owner: infra
version: v1
document_type: runbook
source_type: runbook
---
# Cluster time synchronisation

Compare node clock offset, stratum, source reachability, leap status, and monotonic-clock behavior. Check whether the problem is isolated to a node, zone, host image, virtualization layer, or the time service itself. Correlate authentication failures, certificate errors, leader elections, and event timestamps before changing time settings.

Do not step a production clock blindly. Restore a trusted time source, repair the daemon configuration, and use the platform-approved gradual correction procedure. Drain or isolate a node only after checking workload disruption and quorum implications. Preserve before-and-after offsets and daemon logs.

Recovery requires offset below the policy threshold on every affected node, stable source synchronization, and no new authentication or leader-election errors during the observation window. Record sources, offsets, actions, and the verification evidence.
