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
	ID         string
	Version    string
	Image      string
	Entrypoint []string
}

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
	Name, Namespace, Image                                string
	Command                                               []string
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
	Tools     map[string]Tool
	Ceiling   Limits
}

func (builder Builder) Build(request Request) (JobSpec, error) {
	if !runIDPattern.MatchString(request.RunID) {
		return JobSpec{}, fmt.Errorf("invalid run id")
	}
	if builder.Namespace == "" || !validLimits(builder.Ceiling) {
		return JobSpec{}, fmt.Errorf("invalid controller job limits")
	}
	tool, ok := builder.Tools[request.ToolID+":"+request.ToolVersion]
	if !ok || !digestPinned(tool.Image) || len(tool.Entrypoint) == 0 {
		return JobSpec{}, fmt.Errorf("unknown or unsafe tool")
	}
	if request.InputArtifactURI == "" || !strings.HasPrefix(request.InputArtifactURI, "minio://") {
		return JobSpec{}, fmt.Errorf("input must be a MinIO artifact URI")
	}
	labels := map[string]string{"app.kubernetes.io/name": "kubeoncall-sandbox", "sandbox.kubeoncall.io/run-id": request.RunID, "sandbox.kubeoncall.io/tool": tool.ID}
	for key, value := range request.Labels {
		if !strings.HasPrefix(key, "sandbox.kubeoncall.io/") || len(key) > 63 || len(value) > 63 {
			return JobSpec{}, fmt.Errorf("unsafe job label")
		}
		labels[key] = value
	}
	return JobSpec{Name: "sandbox-" + request.RunID[4:], Namespace: builder.Namespace, Image: tool.Image, Command: append([]string(nil), tool.Entrypoint...), Labels: labels, Limits: builder.Ceiling, Security: SecurityContext{RunAsNonRoot: true, ReadOnlyRootFilesystem: true, AllowPrivilegeEscalation: false, DropCapabilities: []string{"ALL"}}, AutomountServiceAccountToken: false, HostNetwork: false, Privileged: false}, nil
}

func validLimits(limits Limits) bool {
	return limits.CPUMilli > 0 && limits.CPUMilli <= 1000 && limits.MemoryMiB > 0 && limits.MemoryMiB <= 1024 && limits.EphemeralMiB > 0 && limits.EphemeralMiB <= 2048 && limits.TimeoutSeconds > 0 && limits.TimeoutSeconds <= 900 && limits.TTLSeconds > 0 && limits.TTLSeconds <= 86400
}
func digestPinned(image string) bool {
	return strings.Contains(image, "@sha256:") && !strings.Contains(image, ":latest") && len(strings.Split(image, "@sha256:")[1]) == 64
}
