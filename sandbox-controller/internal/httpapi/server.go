package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

type Server struct {
	config            Config
	authenticator     *authenticator
	semaphore         chan struct{}
	mux               *http.ServeMux
	manager           LifecycleManager
	simulationManager LifecycleManager
}

// LifecycleManager is the subset of the Kubernetes manager the HTTP layer needs. The concrete
// implementation lives in internal/kubernetes; tests inject a fake.
type LifecycleManager interface {
	EnsureJob(ctx context.Context, request jobs.Request, expiresAt time.Time) (LifecycleStatus, error)
	Status(ctx context.Context, runID string) (LifecycleStatus, error)
	Cancel(ctx context.Context, runID string) (LifecycleStatus, error)
	Collect(ctx context.Context, runID string, logLimit int64) (LifecycleResult, error)
}

// LifecycleStatus mirrors kubernetes.Status without importing that package here.
type LifecycleStatus struct {
	RunID      string     `json:"runId"`
	Phase      string     `json:"phase"`
	Exists     bool       `json:"exists"`
	StartTime  *time.Time `json:"startTime,omitempty"`
	EndTime    *time.Time `json:"endTime,omitempty"`
	FailedPods []struct {
		Name, Reason string
	} `json:"failedPods,omitempty"`
}

// LifecycleResult mirrors kubernetes.Result: normalized outcome with redacted, size-bounded logs.
type LifecycleResult struct {
	RunID       string     `json:"runId"`
	Phase       string     `json:"phase"`
	ExitCode    *int32     `json:"exitCode,omitempty"`
	Reason      string     `json:"reason,omitempty"`
	Logs        string     `json:"logs,omitempty"`
	Output      string     `json:"output,omitempty"`
	StartedAt   *time.Time `json:"startedAt,omitempty"`
	FinishedAt  *time.Time `json:"finishedAt,omitempty"`
	OutputFound bool       `json:"outputFound"`
}

func NewServer(config Config) *Server {
	return NewServerWithManagers(config, nil, nil)
}

// NewServerWithManager wires a Kubernetes lifecycle manager. When manager is nil the lifecycle
// endpoints report not-ready, preserving the scaffold's safe default from SBX-07.
func NewServerWithManager(config Config, manager LifecycleManager) *Server {
	return NewServerWithManagers(config, manager, nil)
}

// NewServerWithManagers wires ordinary sandbox Jobs and remediation simulation to distinct
// lifecycle managers. Keeping the latter separate makes an absent validation-cluster credential a
// safe 501 instead of an accidental fallback to the ordinary cluster.
func NewServerWithManagers(config Config, manager, simulationManager LifecycleManager) *Server {
	server := &Server{config: config, authenticator: newAuthenticator(config), semaphore: make(chan struct{}, config.MaxConcurrent), mux: http.NewServeMux(), manager: manager, simulationManager: simulationManager}
	server.mux.HandleFunc("GET /healthz", server.health)
	server.mux.HandleFunc("POST /internal/v1/runs", server.acceptRun)
	server.mux.HandleFunc("GET /internal/v1/runs/{runId}", server.getRun)
	server.mux.HandleFunc("DELETE /internal/v1/runs/{runId}", server.cancelRun)
	server.mux.HandleFunc("GET /internal/v1/runs/{runId}/logs", server.getRunLogs)
	server.mux.HandleFunc("POST /internal/v1/simulations", server.acceptSimulation)
	server.mux.HandleFunc("GET /internal/v1/simulations/{runId}", server.getSimulation)
	server.mux.HandleFunc("DELETE /internal/v1/simulations/{runId}", server.cancelSimulation)
	server.mux.HandleFunc("GET /internal/v1/simulations/{runId}/logs", server.getSimulationLogs)
	return server
}

func (server *Server) Handler() http.Handler { return server.requestID(server.limit(server.mux)) }

func (server *Server) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]any{"status": "ok"})
}

func (server *Server) acceptRun(writer http.ResponseWriter, request *http.Request) {
	server.acceptRunWithManager(writer, request, server.manager)
}

func (server *Server) acceptSimulation(writer http.ResponseWriter, request *http.Request) {
	server.acceptRunWithManager(writer, request, server.simulationManager)
}

func (server *Server) acceptRunWithManager(writer http.ResponseWriter, request *http.Request, manager LifecycleManager) {
	body, err := io.ReadAll(http.MaxBytesReader(writer, request.Body, server.config.MaxRequestBytes))
	if err != nil {
		writeError(writer, http.StatusRequestEntityTooLarge, "REQUEST_TOO_LARGE", "request body exceeds configured limit")
		return
	}
	if err := server.authenticator.verify(request, body); err != nil {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", err.Error())
		return
	}
	if manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	var payload struct {
		RunID            string            `json:"runId"`
		ToolID           string            `json:"toolId"`
		ToolVersion      string            `json:"toolVersion"`
		InputArtifactURI string            `json:"inputArtifactUri"`
		Labels           map[string]string `json:"labels"`
		ExpiresAt        time.Time         `json:"expiresAt"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		writeError(writer, http.StatusBadRequest, "INVALID_PAYLOAD", "request body is not valid JSON")
		return
	}
	expiresAt := payload.ExpiresAt
	if expiresAt.IsZero() {
		expiresAt = time.Now().Add(24 * time.Hour)
	}
	status, err := manager.EnsureJob(request.Context(), jobs.Request{
		RunID:            payload.RunID,
		ToolID:           payload.ToolID,
		ToolVersion:      payload.ToolVersion,
		InputArtifactURI: payload.InputArtifactURI,
		Labels:           payload.Labels,
	}, expiresAt)
	server.writeLifecycleResult(writer, status, err)
}

func (server *Server) getRun(writer http.ResponseWriter, request *http.Request) {
	server.getRunWithManager(writer, request, server.manager)
}

func (server *Server) getSimulation(writer http.ResponseWriter, request *http.Request) {
	server.getRunWithManager(writer, request, server.simulationManager)
}

func (server *Server) getRunWithManager(writer http.ResponseWriter, request *http.Request, manager LifecycleManager) {
	runID := request.PathValue("runId")
	if !server.authenticateGet(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "signature verification failed")
		return
	}
	if manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	status, err := manager.Status(request.Context(), runID)
	server.writeLifecycleResult(writer, status, err)
}

func (server *Server) cancelRun(writer http.ResponseWriter, request *http.Request) {
	server.cancelRunWithManager(writer, request, server.manager)
}

func (server *Server) cancelSimulation(writer http.ResponseWriter, request *http.Request) {
	server.cancelRunWithManager(writer, request, server.simulationManager)
}

func (server *Server) cancelRunWithManager(writer http.ResponseWriter, request *http.Request, manager LifecycleManager) {
	runID := request.PathValue("runId")
	if !server.authenticateGet(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "signature verification failed")
		return
	}
	if manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	status, err := manager.Cancel(request.Context(), runID)
	server.writeLifecycleResult(writer, status, err)
}

// getRunLogs collects the normalized result — exit code, failure reason and redacted, size-bounded
// logs — for a run. The controller never streams raw pod logs; logs are truncated and scrubbed of
// secret-bearing patterns before leaving the cluster.
func (server *Server) getRunLogs(writer http.ResponseWriter, request *http.Request) {
	server.getRunLogsWithManager(writer, request, server.manager)
}

func (server *Server) getSimulationLogs(writer http.ResponseWriter, request *http.Request) {
	server.getRunLogsWithManager(writer, request, server.simulationManager)
}

func (server *Server) getRunLogsWithManager(writer http.ResponseWriter, request *http.Request, manager LifecycleManager) {
	runID := request.PathValue("runId")
	if !server.authenticateGet(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "signature verification failed")
		return
	}
	if manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	result, err := manager.Collect(request.Context(), runID, server.config.LogLimitBytes)
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "LIFECYCLE_ERROR", err.Error())
		return
	}
	writeJSON(writer, http.StatusOK, result)
}

// authenticateGet verifies the HMAC signature on GET/DELETE requests that carry no body.
func (server *Server) authenticateGet(request *http.Request) bool {
	return server.authenticator.verify(request, nil) == nil
}

func (server *Server) writeLifecycleResult(writer http.ResponseWriter, status LifecycleStatus, err error) {
	if err != nil {
		writeError(writer, http.StatusInternalServerError, "LIFECYCLE_ERROR", err.Error())
		return
	}
	writeJSON(writer, http.StatusOK, status)
}

func (server *Server) limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		select {
		case server.semaphore <- struct{}{}:
			defer func() { <-server.semaphore }()
			next.ServeHTTP(writer, request)
		default:
			writeError(writer, http.StatusTooManyRequests, "CONCURRENCY_LIMITED", "controller is at concurrency capacity")
		}
	})
}

func (server *Server) requestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requestID := request.Header.Get("X-Request-Id")
		if requestID == "" {
			requestID = randomID()
		}
		writer.Header().Set("X-Request-Id", requestID)
		next.ServeHTTP(writer, request)
	})
}

func writeError(writer http.ResponseWriter, status int, code, message string) {
	writeJSON(writer, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
}

func writeJSON(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}

func randomID() string {
	bytes := make([]byte, 12)
	_, _ = rand.Read(bytes)
	return "req_" + hex.EncodeToString(bytes)
}
