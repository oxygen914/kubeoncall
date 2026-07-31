---
runbookId: runbook-host-file-descriptor-pressure
title: Host file descriptor pressure response SOP
category: infrastructure
owner: infra
version: v1
document_type: runbook
source_type: runbook
---
# Host file descriptor pressure

Confirm allocated file descriptors, the system maximum, utilization trend, and whether the signal is isolated to one node. Correlate the increase with process-level descriptor counts, socket states, connection rate, open files, container restarts, deployment changes, and application error logs such as “too many open files”. Compare with another node and distinguish a sustained leak from a short traffic burst. Capture the top owning processes and workloads without exposing sensitive file contents.

Do not kill processes, restart the node, raise kernel limits, or close descriptors by hand as the first response. Those actions can interrupt healthy traffic and hide the leaking owner. Prefer reducing the offending workload through the governed scaling path, fixing connection or file-handle lifecycle, and changing limits only after capacity, persistence, rollback, and application limits have been reviewed.

Recovery requires utilization below 70 percent for two evaluation windows, no new descriptor-exhaustion errors, stable connection behavior, and the owning workload remaining healthy. Record the node, process or workload owner, growth rate, approved action, validation evidence, and rollback plan.
