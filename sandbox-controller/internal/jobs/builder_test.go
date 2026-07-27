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
