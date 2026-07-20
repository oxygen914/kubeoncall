---
runbookId: runbook-control-plane-apiserver
title: Kubernetes API server availability response SOP
category: control-plane
owner: platform
version: v1
document_type: runbook
source_type: runbook
---
# API server availability

Confirm the failure from more than one client and distinguish DNS, load-balancer, TLS, authentication, admission, and API server process failures. Check API server health, audit logs, etcd latency and quorum, control-plane node resources, recent certificates, and recent control-plane changes. Preserve evidence before restarting anything.

Keep writes and destructive remediation paused until the control-plane state is understood. If one replica is unhealthy, repair it using the platform procedure and verify quorum before changing replicas. Do not force an etcd recovery or delete control-plane pods without an approved incident command decision and a current backup.

Recovery requires successful authenticated read and write probes, stable API latency, healthy admission and controller-manager communication, and no new control-plane derivative alerts for the configured window. Record probe results, quorum state, and all actions.
