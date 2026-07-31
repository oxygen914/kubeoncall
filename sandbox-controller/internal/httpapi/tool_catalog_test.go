package httpapi

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

func TestLoadToolCatalogUsesReleasePinnedImages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tools.yaml")
	content := `tools:
  - id: generated-python
    version: v1
    mode: GENERATED_CODE
    imageDigest: registry.example/generated-python@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    entrypoint: /usr/local/bin/koc-run-python
    networkEgressPolicy: DENY_ALL
    inputSchemaRef: sandbox-tools/schemas/generated-code-input-v1.json
    outputSchemaRef: sandbox-tools/schemas/generated-code-result-v1.json
    resources:
      cpu: 500m
      memory: 512Mi
      ephemeralStorage: 512Mi
      inputMaxBytes: 52428800
      outputMaxBytes: 1048576
      logMaxBytes: 2097152
      scriptMaxBytes: 262144
      timeoutSeconds: 120
      maxTimeoutSeconds: 300
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	tools, err := loadToolCatalog(path)
	if err != nil {
		t.Fatal(err)
	}
	tool := tools["generated-python:v1"]
	if tool.Runtime != jobs.RuntimeGeneratedPython ||
		tool.NetworkEgressPolicy != jobs.NetworkEgressDenyAll ||
		tool.MaxProcesses != 32 ||
		tool.Limits.CPUMilli != 500 ||
		tool.Limits.MemoryMiB != 512 ||
		tool.Limits.EphemeralMiB != 512 ||
		tool.Limits.TimeoutSeconds != 120 {
		t.Fatalf("unexpected generated runtime: %+v", tool)
	}
}

func TestLoadToolCatalogRejectsMutableImageReferences(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tools.yaml")
	content := `tools:
  - id: manifest-validation
    version: v1
    mode: MANIFEST_VALIDATION
    imageDigest: registry.example/manifest-validation:latest
    entrypoint: /usr/local/bin/koc-validate-manifest
    networkEgressPolicy: DENY_ALL
    resources: {cpu: 500m, memory: 512Mi, ephemeralStorage: 1Gi, inputMaxBytes: 52428800, outputMaxBytes: 1048576, logMaxBytes: 2097152, scriptMaxBytes: 262144, timeoutSeconds: 180, maxTimeoutSeconds: 300}
`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}

	if _, err := loadToolCatalog(path); err == nil {
		t.Fatal("mutable image reference must be rejected")
	}
}

func TestLoadToolCatalogRejectsGeneratedCodeEgressAndOversizedResources(t *testing.T) {
	base := `tools:
  - id: generated-python
    version: v1
    mode: GENERATED_CODE
    imageDigest: registry.example/generated-python@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    entrypoint: /usr/local/bin/koc-run-python
    networkEgressPolicy: %s
    resources: {cpu: %s, memory: 512Mi, ephemeralStorage: 512Mi, inputMaxBytes: 52428800, outputMaxBytes: 1048576, logMaxBytes: 2097152, scriptMaxBytes: 262144, timeoutSeconds: 120, maxTimeoutSeconds: 300}
`
	for name, content := range map[string]string{
		"egress":    fmt.Sprintf(base, "DNS_AND_ARTIFACT_CHANNEL", "500m"),
		"resources": fmt.Sprintf(base, "DENY_ALL", "1500m"),
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "tools.yaml")
			if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := loadToolCatalog(path); err == nil {
				t.Fatal("unsafe catalog must be rejected")
			}
		})
	}
}
