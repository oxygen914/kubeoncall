// Package jobs builds the constrained Kubernetes Job projection used by the controller.
package jobs

import (
	"fmt"
	"regexp"
	"strings"
)

var runIDPattern = regexp.MustCompile(`^sbx_[0-9a-f]{1,128}$`)

// Tool is server-owned catalogue data. Callers cannot provide image, command, env or volume data.
type Tool struct {
	ID                  string
	Version             string
	Image               string
	Entrypoint          []string
	Runtime             Runtime
	NetworkEgressPolicy NetworkEgressPolicy
	Limits              Limits
	MaxProcesses        int64
	OutputMaxBytes      int64
}

// Runtime fixes how an allowlisted image interprets an input Artifact. It is server-owned catalog
// metadata: callers can only reference a tool ID/version and cannot select a shell, interpreter or
// command line.
type Runtime string

const (
	RuntimeFixedDiagnostic Runtime = "FIXED_DIAGNOSTIC"
	RuntimeGeneratedPython Runtime = "GENERATED_PYTHON"
	RuntimeGeneratedPOSIX  Runtime = "GENERATED_POSIX_SHELL"
)

// NetworkEgressPolicy is server-owned catalogue data. The Kubernetes baseline always isolates
// Sandbox Pods; a tool may opt into only the infrastructure-managed DNS and artifact channel.
type NetworkEgressPolicy string

const (
	NetworkEgressDenyAll               NetworkEgressPolicy = "DENY_ALL"
	NetworkEgressDNSAndArtifactChannel NetworkEgressPolicy = "DNS_AND_ARTIFACT_CHANNEL"
)

type Limits struct{ CPUMilli, MemoryMiB, EphemeralMiB, TimeoutSeconds, TTLSeconds int64 }
type Request struct {
	RunID            string            `json:"runId"`
	ToolID           string            `json:"toolId"`
	ToolVersion      string            `json:"toolVersion"`
	InputArtifactURI string            `json:"inputArtifactUri"`
	Labels           map[string]string `json:"labels"`
}

// JobSpec is a serializable projection deliberately limited to fields the security contract permits.
type JobSpec struct {
	Name, Namespace, Image string
	// ServiceAccountName is controller-owned. Ordinary sandbox Jobs leave it blank and never
	// receive an API token; simulation Jobs use a namespace-local account that has no bindings.
	ServiceAccountName                                    string
	Command                                               []string
	Environment                                           map[string]string
	Labels                                                map[string]string
	Limits                                                Limits
	Security                                              SecurityContext
	AutomountServiceAccountToken, HostNetwork, Privileged bool
}
type SecurityContext struct {
	RunAsNonRoot, ReadOnlyRootFilesystem, AllowPrivilegeEscalation bool
	DropCapabilities                                               []string
}

type Builder struct {
	Namespace string
	// ServiceAccountName may only be selected by the Controller's trusted runtime wiring. It is
	// deliberately absent from Request so a Backend caller cannot choose a Kubernetes identity.
	ServiceAccountName string
	Tools              map[string]Tool
	Ceiling            Limits
}

func (builder Builder) Build(request Request) (JobSpec, error) {
	if !runIDPattern.MatchString(request.RunID) {
		return JobSpec{}, fmt.Errorf("invalid run id")
	}
	if builder.Namespace == "" || !validLimits(builder.Ceiling) {
		return JobSpec{}, fmt.Errorf("invalid controller job limits")
	}
	tool, ok := builder.Tools[request.ToolID+":"+request.ToolVersion]
	if !ok || !digestPinned(tool.Image) || len(tool.Entrypoint) == 0 || !safeRuntime(tool.Runtime) || !safeNetworkEgress(tool.NetworkEgressPolicy) {
		return JobSpec{}, fmt.Errorf("unknown or unsafe tool")
	}
	if err := ValidateToolLimits(tool.Limits, builder.Ceiling); err != nil {
		return JobSpec{}, fmt.Errorf("unsafe tool resource limits: %w", err)
	}
	if request.InputArtifactURI == "" || !(strings.HasPrefix(request.InputArtifactURI, "minio://") || strings.HasPrefix(request.InputArtifactURI, "https://") || strings.HasPrefix(request.InputArtifactURI, "http://")) {
		return JobSpec{}, fmt.Errorf("input must be a signed artifact URI")
	}
	labels := map[string]string{
		"app.kubernetes.io/name":               "kubeoncall-sandbox",
		"sandbox.kubeoncall.io/run-id":         request.RunID,
		"sandbox.kubeoncall.io/tool":           tool.ID,
		"sandbox.kubeoncall.io/network-egress": string(tool.NetworkEgressPolicy),
	}
	for key, value := range request.Labels {
		if !strings.HasPrefix(key, "sandbox.kubeoncall.io/") || len(key) > 63 || len(value) > 63 {
			return JobSpec{}, fmt.Errorf("unsafe job label")
		}
		labels[key] = value
	}
	environment := map[string]string{
		"SANDBOX_INPUT_ARTIFACT_URI": request.InputArtifactURI,
		"SANDBOX_OUTPUT_DIR":         "/sandbox/output",
		"SANDBOX_OUTPUT_PATH":        "/sandbox/output/result.json",
		"SANDBOX_NETWORK_EGRESS":     string(tool.NetworkEgressPolicy),
	}
	if isGeneratedRuntime(tool.Runtime) {
		if tool.NetworkEgressPolicy != NetworkEgressDenyAll || tool.MaxProcesses < 1 || tool.MaxProcesses > 32 || tool.OutputMaxBytes < 1 || tool.OutputMaxBytes > 10*1024*1024 {
			return JobSpec{}, fmt.Errorf("unsafe generated-code runtime limits")
		}
		environment["SANDBOX_MAX_PROCESSES"] = fmt.Sprintf("%d", tool.MaxProcesses)
		environment["SANDBOX_MAX_OUTPUT_BYTES"] = fmt.Sprintf("%d", tool.OutputMaxBytes)
	}
	return JobSpec{Name: "sandbox-" + request.RunID[4:], Namespace: builder.Namespace, Image: tool.Image, ServiceAccountName: builder.ServiceAccountName, Command: append([]string(nil), tool.Entrypoint...), Environment: environment, Labels: labels, Limits: tool.Limits, Security: SecurityContext{RunAsNonRoot: true, ReadOnlyRootFilesystem: true, AllowPrivilegeEscalation: false, DropCapabilities: []string{"ALL"}}, AutomountServiceAccountToken: false, HostNetwork: false, Privileged: false}, nil
}

func safeRuntime(runtime Runtime) bool {
	return runtime == "" || runtime == RuntimeFixedDiagnostic || runtime == RuntimeGeneratedPython || runtime == RuntimeGeneratedPOSIX
}

func isGeneratedRuntime(runtime Runtime) bool {
	return runtime == RuntimeGeneratedPython || runtime == RuntimeGeneratedPOSIX
}

func safeNetworkEgress(policy NetworkEgressPolicy) bool {
	return policy == NetworkEgressDenyAll || policy == NetworkEgressDNSAndArtifactChannel
}

// ValidateToolLimits rejects catalogue limits that are malformed or exceed the immutable
// deployment ceiling. It is shared by catalogue loading and Job construction for defense in depth.
func ValidateToolLimits(limits Limits, ceiling Limits) error {
	if !validLimits(limits) {
		return fmt.Errorf("invalid limits")
	}
	if limits.CPUMilli > ceiling.CPUMilli ||
		limits.MemoryMiB > ceiling.MemoryMiB ||
		limits.EphemeralMiB > ceiling.EphemeralMiB ||
		limits.TimeoutSeconds > ceiling.TimeoutSeconds ||
		limits.TTLSeconds > ceiling.TTLSeconds {
		return fmt.Errorf("limits exceed controller ceiling")
	}
	return nil
}

func validLimits(limits Limits) bool {
	return limits.CPUMilli > 0 && limits.CPUMilli <= 1000 && limits.MemoryMiB > 0 && limits.MemoryMiB <= 1024 && limits.EphemeralMiB > 0 && limits.EphemeralMiB <= 2048 && limits.TimeoutSeconds > 0 && limits.TimeoutSeconds <= 900 && limits.TTLSeconds > 0 && limits.TTLSeconds <= 86400
}
func digestPinned(image string) bool {
	return strings.Contains(image, "@sha256:") && !strings.Contains(image, ":latest") && len(strings.Split(image, "@sha256:")[1]) == 64
}
