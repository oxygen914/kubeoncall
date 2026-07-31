package kubernetes

import (
	"context"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/httpapi"
	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

// HTTPAdapter exposes Manager through the httpapi.LifecycleManager interface so the HTTP layer
// stays decoupled from Kubernetes types. It converts the typed Status into the transport struct.
type HTTPAdapter struct {
	manager *Manager
}

// NewHTTPAdapter wraps a Manager for injection into the HTTP server.
func NewHTTPAdapter(manager *Manager) *HTTPAdapter {
	return &HTTPAdapter{manager: manager}
}

func (adapter *HTTPAdapter) EnsureJob(ctx context.Context, request jobs.Request, expiresAt time.Time) (httpapi.LifecycleStatus, error) {
	status, err := adapter.manager.EnsureJob(ctx, request, expiresAt)
	return toHTTPStatus(status), err
}

func (adapter *HTTPAdapter) Status(ctx context.Context, runID string) (httpapi.LifecycleStatus, error) {
	status, err := adapter.manager.Status(ctx, runID)
	return toHTTPStatus(status), err
}

func (adapter *HTTPAdapter) Cancel(ctx context.Context, runID string) (httpapi.LifecycleStatus, error) {
	status, err := adapter.manager.Cancel(ctx, runID)
	return toHTTPStatus(status), err
}

func (adapter *HTTPAdapter) Collect(ctx context.Context, runID string, logLimit int64) (httpapi.LifecycleResult, error) {
	result, err := adapter.manager.CollectResult(ctx, runID, logLimit)
	return toHTTPResult(result), err
}

func toHTTPResult(result Result) httpapi.LifecycleResult {
	return httpapi.LifecycleResult{
		RunID:       result.RunID,
		Phase:       string(result.Phase),
		ExitCode:    result.ExitCode,
		Reason:      string(result.Reason),
		Logs:        result.Logs,
		Output:      result.Output,
		StartedAt:   result.StartedAt,
		FinishedAt:  result.FinishedAt,
		OutputFound: result.OutputFound,
	}
}

func toHTTPStatus(status Status) httpapi.LifecycleStatus {
	result := httpapi.LifecycleStatus{
		RunID:     status.RunID,
		Phase:     string(status.Phase),
		Exists:    status.Exists,
		StartTime: status.StartTime,
		EndTime:   status.EndTime,
	}
	for _, failure := range status.FailedPods {
		result.FailedPods = append(result.FailedPods, struct {
			Name, Reason string
		}{Name: failure.Name, Reason: failure.Reason})
	}
	return result
}
