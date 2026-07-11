---
runbookId: runbook-service-slo
title: Service SLO burn response SOP
category: service
owner: sre
version: v1
document_type: runbook
source_type: runbook
---
# Service SLO burn

Confirm the burn-rate window, affected endpoint, traffic volume, and error budget remaining. Split failures by status code, dependency, region, tenant, and release. Compare the current signal with recent deployments, feature flags, dependency health, saturation, and known maintenance. A low-volume spike must not be treated as a broad outage without checking counts.

Use the least risky mitigation that protects the error budget: rollback a verified regression, disable a scoped feature flag, shed non-critical work, or route traffic to a healthy zone. Keep evidence and obtain approval for changes that affect data, routing, or customer behavior. Coordinate with the service owner and incident commander.

Recovery requires the burn rate to return below the policy threshold across the complete observation window and all affected dimensions. Record the budget impact, mitigation, validation queries, and follow-up corrective work.
