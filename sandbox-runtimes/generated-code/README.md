# Generated-code Sandbox Runtime

This directory builds the only two Agent-generated-code images admitted by the KubeOnCall
server-side tool catalog: `generated-python:v1` and `generated-posix-shell:v1`.

Both images run as an unprivileged user, accept exactly one short-lived object-scoped input URL in
`SANDBOX_INPUT_ARTIFACT_URI`, and emit one bounded `KOC_RESULT_JSON:` line. The Controller extracts
that line without executing it; Backend validates the result schema before persisting it as an
untrusted output Artifact. The images never receive a production kubeconfig, MinIO access key,
ServiceAccount token, image/command override or unrestricted network.

The source image tags in these Dockerfiles are build inputs only. Deployment always uses the
64-character image digests fixed in `backend/src/main/resources/sandbox-tools/tools.yaml` and
`sandbox-controller/internal/httpapi/server.go`.
