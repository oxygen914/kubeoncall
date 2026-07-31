---
runbookId: runbook-certificate-expiry
title: Certificate expiry response SOP
category: certificate
owner: security
version: v1
document_type: runbook
source_type: runbook
---
# Certificate expiry response

Confirm the affected endpoint, certificate chain, SAN, issuer, and remaining validity from the live service. Check whether the alert is caused by an edge cache or by the certificate actually served by the workload. Identify the owning team and the approved change record before rotating anything.

Use the approved certificate automation in a non-production environment first. Validate the new chain, private-key reference, trust-store contents, ingress secret, and every dependent client. Roll out gradually, then verify the endpoint from inside and outside the cluster. Do not overwrite a private key or revoke the old certificate until the replacement is serving successfully.

Recovery requires all monitored entry points to return the new certificate and retain at least 30 days of validity. Record the issuer, serial, expiry, change reference, validation commands, and rollback result in the incident.
