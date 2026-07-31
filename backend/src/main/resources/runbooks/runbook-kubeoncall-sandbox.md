---
runbookId: runbook-kubeoncall-sandbox
title: KubeOnCall Sandbox lifecycle response SOP
category: kubeoncall-sandbox
owner: platform
version: v1
document_type: runbook
source_type: runbook
---
# KubeOnCall Sandbox lifecycle

Identify the affected sandbox run, controller endpoint, provider, workspace, and current lifecycle state. Correlate active-run age, creation and terminal-event rates, controller request outcomes, timeout events, cleanup failures, and oldest cleanup-pending age. Check whether the problem is isolated to one run or provider and compare the first failure with controller deployments, provider incidents, quota changes, image changes, and network degradation. Preserve run identifiers and provider request identifiers for later reconciliation.

Do not mark runs complete, delete cleanup records, release external resources manually, or retry an unbounded backlog before proving the operation is idempotent. A timeout does not prove that the provider stopped processing the request. Prefer restoring controller connectivity, pausing new intake through the governed release path, and replaying only a bounded set whose remote state has been checked.

Recovery requires controller error rate back to baseline, active-run and cleanup ages draining, no new timeout or cleanup-failure events, and external resources reconciled against durable records. Record leaked resource identifiers, verified remote state, approved remediation, rollback steps, and any run that remains ambiguous for manual escalation.
