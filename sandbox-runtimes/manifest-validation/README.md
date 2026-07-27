# Manifest Validation Runtime

`manifest-validation:v1` is a pinned, server-owned runtime for YAML, Helm, JSON Patch, Strategic
Merge Patch and Runbook validation. It receives one object-scoped input URL and prints only the
bounded `KOC_RESULT_JSON:` contract. It does not mount a production ServiceAccount token and cannot
perform `kubectl apply`, Patch or any other production mutation.

The image bundles exact Helm, kubeconform and Conftest binaries. Their execution records the tool
and ruleset versions in the result; Java preflight rejects malformed input and calculates patch
diffs before this image is scheduled.
