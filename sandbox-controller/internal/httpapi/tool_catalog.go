package httpapi

import (
	"encoding/hex"
	"fmt"
	"os"
	"strings"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
	"k8s.io/apimachinery/pkg/api/resource"
	"sigs.k8s.io/yaml"
)

func defaultControllerCeiling() jobs.Limits {
	return jobs.Limits{
		CPUMilli:       1000,
		MemoryMiB:      1024,
		EphemeralMiB:   2048,
		TimeoutSeconds: 300,
		TTLSeconds:     86400,
	}
}

// defaultTools mirrors the versioned Backend classpath catalog for local development. Release
// deployments mount a generated, server-owned catalogue containing the actual registry digests.
// Both paths keep image selection outside the signed request contract.
func defaultTools() map[string]jobs.Tool {
	return map[string]jobs.Tool{
		"log-pattern-analysis:v1": {
			ID:                  "log-pattern-analysis",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/log-pattern-analysis@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			Entrypoint:          []string{"/tool"},
			Runtime:             jobs.RuntimeFixedDiagnostic,
			NetworkEgressPolicy: jobs.NetworkEgressDNSAndArtifactChannel,
			Limits:              defaultToolLimits(250, 256, 512, 300),
		},
		"kubernetes-consistency:v1": {
			ID:                  "kubernetes-consistency",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/kubernetes-consistency@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			Entrypoint:          []string{"/tool"},
			Runtime:             jobs.RuntimeFixedDiagnostic,
			NetworkEgressPolicy: jobs.NetworkEgressDNSAndArtifactChannel,
			Limits:              defaultToolLimits(250, 256, 512, 300),
		},
		"configuration-diff:v1": {
			ID:                  "configuration-diff",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/configuration-diff@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
			Entrypoint:          []string{"/tool"},
			Runtime:             jobs.RuntimeFixedDiagnostic,
			NetworkEgressPolicy: jobs.NetworkEgressDNSAndArtifactChannel,
			Limits:              defaultToolLimits(250, 256, 512, 300),
		},
		"generated-python:v1": {
			ID:                  "generated-python",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/generated-python@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
			Entrypoint:          []string{"/usr/local/bin/koc-run-python"},
			Runtime:             jobs.RuntimeGeneratedPython,
			NetworkEgressPolicy: jobs.NetworkEgressDenyAll,
			Limits:              defaultToolLimits(500, 512, 512, 120),
			MaxProcesses:        32,
			OutputMaxBytes:      1024 * 1024,
		},
		"generated-posix-shell:v1": {
			ID:                  "generated-posix-shell",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/generated-posix-shell@sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
			Entrypoint:          []string{"/usr/local/bin/koc-run-posix-shell"},
			Runtime:             jobs.RuntimeGeneratedPOSIX,
			NetworkEgressPolicy: jobs.NetworkEgressDenyAll,
			Limits:              defaultToolLimits(500, 512, 512, 120),
			MaxProcesses:        32,
			OutputMaxBytes:      1024 * 1024,
		},
		"manifest-validation:v1": {
			ID:                  "manifest-validation",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/manifest-validation@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
			Entrypoint:          []string{"/usr/local/bin/koc-validate-manifest"},
			Runtime:             jobs.RuntimeFixedDiagnostic,
			NetworkEgressPolicy: jobs.NetworkEgressDenyAll,
			Limits:              defaultToolLimits(500, 512, 1024, 180),
		},
		"remediation-simulation:v1": {
			ID:                  "remediation-simulation",
			Version:             "v1",
			Image:               "registry.kubeoncall.io/sandbox/remediation-simulation@sha256:1111111111111111111111111111111111111111111111111111111111111111",
			Entrypoint:          []string{"/usr/local/bin/koc-simulate-remediation"},
			Runtime:             jobs.RuntimeFixedDiagnostic,
			NetworkEgressPolicy: jobs.NetworkEgressDenyAll,
			Limits:              defaultToolLimits(500, 512, 1024, 300),
		},
	}
}

func defaultToolLimits(cpuMilli, memoryMiB, ephemeralMiB, timeoutSeconds int64) jobs.Limits {
	return jobs.Limits{
		CPUMilli:       cpuMilli,
		MemoryMiB:      memoryMiB,
		EphemeralMiB:   ephemeralMiB,
		TimeoutSeconds: timeoutSeconds,
		TTLSeconds:     86400,
	}
}

type toolCatalogDocument struct {
	Tools []toolCatalogEntry `json:"tools"`
}

type toolCatalogEntry struct {
	ID                  string `json:"id"`
	Version             string `json:"version"`
	Mode                string `json:"mode"`
	Image               string `json:"imageDigest"`
	Entrypoint          string `json:"entrypoint"`
	NetworkEgressPolicy string `json:"networkEgressPolicy"`
	InputSchemaRef      string `json:"inputSchemaRef"`
	OutputSchemaRef     string `json:"outputSchemaRef"`
	Resources           struct {
		CPU               string `json:"cpu"`
		Memory            string `json:"memory"`
		EphemeralStorage  string `json:"ephemeralStorage"`
		InputMaxBytes     int64  `json:"inputMaxBytes"`
		OutputMaxBytes    int64  `json:"outputMaxBytes"`
		LogMaxBytes       int64  `json:"logMaxBytes"`
		ScriptMaxBytes    int64  `json:"scriptMaxBytes"`
		TimeoutSeconds    int64  `json:"timeoutSeconds"`
		MaxTimeoutSeconds int64  `json:"maxTimeoutSeconds"`
	} `json:"resources"`
}

func loadToolCatalog(path string) (map[string]jobs.Tool, error) {
	body, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var document toolCatalogDocument
	if err := yaml.UnmarshalStrict(body, &document); err != nil {
		return nil, fmt.Errorf("decode %s: %w", path, err)
	}
	if len(document.Tools) == 0 {
		return nil, fmt.Errorf("catalog contains no tools")
	}
	loaded := make(map[string]jobs.Tool, len(document.Tools))
	for _, entry := range document.Tools {
		entry.ID = strings.TrimSpace(entry.ID)
		entry.Version = strings.TrimSpace(entry.Version)
		entry.Entrypoint = strings.TrimSpace(entry.Entrypoint)
		if entry.ID == "" || entry.Version == "" || entry.Entrypoint == "" || !validPinnedImage(entry.Image) {
			return nil, fmt.Errorf("tool %s:%s has an unsafe identity, entrypoint or image", entry.ID, entry.Version)
		}
		runtime, err := catalogRuntime(entry)
		if err != nil {
			return nil, err
		}
		networkEgress, err := catalogNetworkEgress(entry)
		if err != nil {
			return nil, err
		}
		limits, err := catalogLimits(entry)
		if err != nil {
			return nil, err
		}
		if err := jobs.ValidateToolLimits(limits, defaultControllerCeiling()); err != nil {
			return nil, fmt.Errorf("tool %s:%s has unsafe resource limits: %w", entry.ID, entry.Version, err)
		}
		tool := jobs.Tool{
			ID:                  entry.ID,
			Version:             entry.Version,
			Image:               entry.Image,
			Entrypoint:          []string{entry.Entrypoint},
			Runtime:             runtime,
			NetworkEgressPolicy: networkEgress,
			Limits:              limits,
			OutputMaxBytes:      entry.Resources.OutputMaxBytes,
		}
		if runtime == jobs.RuntimeGeneratedPython || runtime == jobs.RuntimeGeneratedPOSIX {
			if networkEgress != jobs.NetworkEgressDenyAll {
				return nil, fmt.Errorf("generated-code tool %s:%s must deny network egress", entry.ID, entry.Version)
			}
			tool.MaxProcesses = 32
			if tool.OutputMaxBytes < 1 || tool.OutputMaxBytes > 10*1024*1024 {
				return nil, fmt.Errorf("generated-code tool %s:%s has an unsafe output limit", entry.ID, entry.Version)
			}
		}
		key := entry.ID + ":" + entry.Version
		if _, exists := loaded[key]; exists {
			return nil, fmt.Errorf("duplicate tool %s", key)
		}
		loaded[key] = tool
	}
	return loaded, nil
}

func catalogNetworkEgress(entry toolCatalogEntry) (jobs.NetworkEgressPolicy, error) {
	switch strings.ToUpper(strings.TrimSpace(entry.NetworkEgressPolicy)) {
	case string(jobs.NetworkEgressDenyAll):
		return jobs.NetworkEgressDenyAll, nil
	case string(jobs.NetworkEgressDNSAndArtifactChannel):
		return jobs.NetworkEgressDNSAndArtifactChannel, nil
	default:
		return "", fmt.Errorf(
			"tool %s:%s has unsupported network egress policy %q",
			entry.ID,
			entry.Version,
			entry.NetworkEgressPolicy,
		)
	}
}

func catalogLimits(entry toolCatalogEntry) (jobs.Limits, error) {
	cpu, err := resource.ParseQuantity(strings.TrimSpace(entry.Resources.CPU))
	if err != nil || cpu.MilliValue() < 1 {
		return jobs.Limits{}, fmt.Errorf("tool %s:%s has invalid CPU quantity", entry.ID, entry.Version)
	}
	memoryMiB, err := quantityMiB(entry.Resources.Memory)
	if err != nil {
		return jobs.Limits{}, fmt.Errorf("tool %s:%s has invalid memory quantity: %w", entry.ID, entry.Version, err)
	}
	ephemeralMiB, err := quantityMiB(entry.Resources.EphemeralStorage)
	if err != nil {
		return jobs.Limits{}, fmt.Errorf(
			"tool %s:%s has invalid ephemeral-storage quantity: %w",
			entry.ID,
			entry.Version,
			err,
		)
	}
	if entry.Resources.InputMaxBytes < 1 || entry.Resources.InputMaxBytes > 50*1024*1024 ||
		entry.Resources.OutputMaxBytes < 1 || entry.Resources.OutputMaxBytes > 10*1024*1024 ||
		entry.Resources.LogMaxBytes < 1 || entry.Resources.LogMaxBytes > 2*1024*1024 ||
		entry.Resources.ScriptMaxBytes < 1 || entry.Resources.ScriptMaxBytes > 256*1024 ||
		entry.Resources.TimeoutSeconds < 1 ||
		entry.Resources.MaxTimeoutSeconds < entry.Resources.TimeoutSeconds ||
		entry.Resources.MaxTimeoutSeconds > 900 {
		return jobs.Limits{}, fmt.Errorf("tool %s:%s has invalid byte or timeout ceilings", entry.ID, entry.Version)
	}
	return jobs.Limits{
		CPUMilli:       cpu.MilliValue(),
		MemoryMiB:      memoryMiB,
		EphemeralMiB:   ephemeralMiB,
		TimeoutSeconds: entry.Resources.TimeoutSeconds,
		TTLSeconds:     86400,
	}, nil
}

func quantityMiB(value string) (int64, error) {
	quantity, err := resource.ParseQuantity(strings.TrimSpace(value))
	if err != nil || quantity.Value() < 1 {
		return 0, fmt.Errorf("invalid Kubernetes quantity")
	}
	const mebibyte = int64(1024 * 1024)
	bytes := quantity.Value()
	return (bytes + mebibyte - 1) / mebibyte, nil
}

func catalogRuntime(entry toolCatalogEntry) (jobs.Runtime, error) {
	switch strings.ToUpper(strings.TrimSpace(entry.Mode)) {
	case "FIXED_DIAGNOSTIC", "MANIFEST_VALIDATION", "REMEDIATION_SIMULATION":
		return jobs.RuntimeFixedDiagnostic, nil
	case "GENERATED_CODE":
		switch entry.ID {
		case "generated-python":
			return jobs.RuntimeGeneratedPython, nil
		case "generated-posix-shell":
			return jobs.RuntimeGeneratedPOSIX, nil
		default:
			return "", fmt.Errorf("generated-code tool %s has no controller-owned runtime", entry.ID)
		}
	default:
		return "", fmt.Errorf("tool %s:%s has unsupported mode %q", entry.ID, entry.Version, entry.Mode)
	}
}

func validPinnedImage(image string) bool {
	parts := strings.Split(image, "@sha256:")
	if len(parts) != 2 || parts[0] == "" || len(parts[1]) != 64 || strings.Contains(image, ":latest") {
		return false
	}
	_, err := hex.DecodeString(parts[1])
	return err == nil
}
