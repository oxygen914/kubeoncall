---
runbookId: runbook-cluster-capacity
title: Cluster capacity pressure response SOP
category: capacity
owner: platform
version: v1
document_type: runbook
source_type: runbook
---
# Cluster capacity pressure

Confirm whether pressure is CPU, memory, pod density, storage, or a control-plane limit. Compare allocatable capacity with requests and actual usage by node, namespace, and workload. Check pending pods, taints, topology constraints, quota failures, and recent scheduling events before changing any limit.

Protect the control plane first. Stop non-essential batch work only through an approved change, and never evict a critical workload without checking its redundancy and disruption budget. Prefer safe scale-out or capacity reservation. If adding nodes, verify image, runtime, labels, taints, network reachability, and monitoring before allowing production scheduling.

Recovery requires pending pods to drain, scheduling failures to stop, and utilization to remain below the policy threshold for the configured window. Record the capacity decision, affected workloads, node additions or removals, and post-change utilization.
