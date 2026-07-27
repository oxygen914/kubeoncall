package jobs

import "testing"

func TestBuilderEmitsHardenedJobAndRejectsUnsafeInputs(t *testing.T) {
	builder := Builder{Namespace: "kubeoncall-sandbox", Tools: map[string]Tool{"pod-inspect:v1": {ID: "pod-inspect", Version: "v1", Image: "registry/pod-inspect@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Entrypoint: []string{"/tool"}}}, Ceiling: Limits{CPUMilli: 500, MemoryMiB: 512, EphemeralMiB: 512, TimeoutSeconds: 300, TTLSeconds: 3600}}
	spec, err := builder.Build(Request{RunID: "sbx_abcd", ToolID: "pod-inspect", ToolVersion: "v1", InputArtifactURI: "minio://sandbox/sbx_abcd/inputs/request.json"})
	if err != nil || spec.Privileged || spec.HostNetwork || spec.AutomountServiceAccountToken || !spec.Security.RunAsNonRoot || spec.Security.AllowPrivilegeEscalation {
		t.Fatalf("unsafe job: %#v, %v", spec, err)
	}
	if _, err := builder.Build(Request{RunID: "sbx_bad/hostpath", ToolID: "pod-inspect", ToolVersion: "v1", InputArtifactURI: "minio://x"}); err == nil {
		t.Fatal("expected malformed run id rejection")
	}
	if _, err := builder.Build(Request{RunID: "sbx_abcd", ToolID: "unknown", ToolVersion: "v1", InputArtifactURI: "minio://x"}); err == nil {
		t.Fatal("expected unknown tool rejection")
	}
	if _, err := builder.Build(Request{RunID: "sbx_abcd", ToolID: "pod-inspect", ToolVersion: "v1", InputArtifactURI: "shell://evil"}); err == nil {
		t.Fatal("expected artifact URI rejection")
	}
}

func TestBuilderLimitsGeneratedCodeToFixedRuntimeAndOutputContract(t *testing.T) {
	builder := Builder{Namespace: "kubeoncall-sandbox", Tools: map[string]Tool{
		"generated-python:v1": {
			ID: "generated-python", Version: "v1", Image: "registry/generated-python@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
			Entrypoint: []string{"/usr/local/bin/koc-run-python"}, Runtime: RuntimeGeneratedPython, MaxProcesses: 32, OutputMaxBytes: 1024 * 1024,
		},
	}, Ceiling: Limits{CPUMilli: 500, MemoryMiB: 512, EphemeralMiB: 512, TimeoutSeconds: 120, TTLSeconds: 3600}}
	spec, err := builder.Build(Request{RunID: "sbx_abcd", ToolID: "generated-python", ToolVersion: "v1", InputArtifactURI: "minio://sandbox/sbx_abcd/inputs/generated-code.json"})
	if err != nil {
		t.Fatal(err)
	}
	if spec.Environment["SANDBOX_MAX_PROCESSES"] != "32" || spec.Environment["SANDBOX_MAX_OUTPUT_BYTES"] != "1048576" || spec.Environment["SANDBOX_NETWORK_EGRESS"] != "DENY_ALL" || spec.Environment["SANDBOX_OUTPUT_PATH"] != "/sandbox/output/result.json" {
		t.Fatalf("unexpected generated runtime environment: %#v", spec.Environment)
	}
	builder.Tools["generated-python:v1"] = Tool{ID: "generated-python", Version: "v1", Image: "registry/generated-python@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", Entrypoint: []string{"/tool"}, Runtime: RuntimeGeneratedPython, MaxProcesses: 33, OutputMaxBytes: 1}
	if _, err := builder.Build(Request{RunID: "sbx_abcd", ToolID: "generated-python", ToolVersion: "v1", InputArtifactURI: "minio://sandbox/sbx_abcd/inputs/generated-code.json"}); err == nil {
		t.Fatal("expected generated runtime process ceiling rejection")
	}
}
