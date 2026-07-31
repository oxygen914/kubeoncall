package simulation

import (
	"context"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/httpapi"
	"github.com/kubeoncall/sandbox-controller/internal/jobs"
	sbxk8s "github.com/kubeoncall/sandbox-controller/internal/kubernetes"
)

// HTTPAdapter keeps the HTTP package independent from the non-production Kubernetes runtime.
type HTTPAdapter struct{ manager *Manager }

func NewHTTPAdapter(manager *Manager) *HTTPAdapter { return &HTTPAdapter{manager: manager} }

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
	return httpapi.LifecycleResult{RunID: result.RunID, Phase: string(result.Phase), ExitCode: result.ExitCode, Reason: string(result.Reason), Logs: result.Logs, Output: result.Output, StartedAt: result.StartedAt, FinishedAt: result.FinishedAt, OutputFound: result.OutputFound}, err
}

func toHTTPStatus(status sbxk8s.Status) httpapi.LifecycleStatus {
	result := httpapi.LifecycleStatus{RunID: status.RunID, Phase: string(status.Phase), Exists: status.Exists, StartTime: status.StartTime, EndTime: status.EndTime}
	for _, failure := range status.FailedPods {
		result.FailedPods = append(result.FailedPods, struct{ Name, Reason string }{Name: failure.Name, Reason: failure.Reason})
	}
	return result
}
