---
runbookId: runbook-kubeoncall-runtime
title: KubeOnCall runtime reliability response SOP
category: kubeoncall-runtime
owner: platform
version: v1
document_type: runbook
source_type: runbook
---
# KubeOnCall runtime reliability

Start with the alert labels, firing duration, deployment version, and the affected dependency or worker. Confirm the metrics endpoint and health probes, then correlate HTTP 5xx, dependency request outcomes, circuit transitions, database-pool utilization, outbox backlog, async-task backlog, dead-letter counts, worker lease loss, and request-correlation gaps. Compare the first failure time with deployments, configuration changes, database incidents, and downstream latency. Preserve the alert fingerprint and correlation identifiers so the same transaction can be followed across the HTTP boundary and durable workers.

Treat dead letters, circuit openings, and lease loss as symptoms until the failing boundary is identified. Do not delete queue rows, rewrite task state, force-close database sessions, disable circuit breakers, or replay work without an approved recovery plan and idempotency evidence. Prefer reducing incoming load, restoring the failed dependency, scaling through the normal deployment path, or retrying a bounded set of verified idempotent records.

Recovery requires healthy probes, 5xx and dependency errors back to baseline, connection-pool headroom, no new dead letters, and backlog age draining for two evaluation windows. Record the root cause, affected IDs, approved action, rollback path, and any records that still require manual reconciliation.
