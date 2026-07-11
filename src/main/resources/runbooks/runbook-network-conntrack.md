---
runbookId: runbook-network-conntrack
title: Conntrack capacity pressure response SOP
category: network
owner: infra
version: v1
document_type: runbook
source_type: runbook
---
# Conntrack capacity pressure

Check entries versus the configured limit on every affected node. Correlate connection failures, retransmits, packet drops, NAT rules, short-lived connection bursts, and the workloads producing them. Compare the current rate with deployment, load-test, and firewall changes. Validate whether only one node or an entire zone is affected.

Do not flush the conntrack table as a first response; that can break healthy traffic and hide the cause. Prefer reducing the connection burst, fixing leaked connections, increasing capacity through an approved change, or moving traffic gradually. Validate kernel settings and persistence before a reboot or node replacement.

Recovery requires utilization below 75 percent, connection errors back to baseline, and no new packet-loss or saturation alerts. Record node values, top sources, the approved capacity change, and the rollback plan.
