package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

// Config contains only internal transport controls. Kubernetes credentials and Job construction
// are intentionally absent until SBX-08/09, so this scaffold cannot mutate a cluster.
type Config struct {
	ListenAddress     string
	BackendKeyID      string
	BackendHMACSecret string
	ClockSkew         time.Duration
	RequestTimeout    time.Duration
	ShutdownTimeout   time.Duration
	MaxRequestBytes   int64
	MaxConcurrent     int
	// SandboxNamespace is the isolated namespace one-shot Jobs run in (§7.1).
	SandboxNamespace string
	// Tools is the server-owned tool catalog keyed by "id:version".
	Tools map[string]jobs.Tool
	// JobCeiling is the immutable resource ceiling applied to every Job.
	JobCeiling jobs.Limits
	// LogLimitBytes is the byte ceiling on collected pod logs (§7.1 default 2 MiB).
	LogLimitBytes int64
}

func LoadConfigFromEnv() (Config, error) {
	config := Config{
		ListenAddress:     env("SANDBOX_CONTROLLER_LISTEN_ADDRESS", ":8088"),
		BackendKeyID:      env("SANDBOX_CONTROLLER_BACKEND_KEY_ID", "backend"),
		BackendHMACSecret: os.Getenv("SANDBOX_CONTROLLER_BACKEND_HMAC_SECRET"),
		ClockSkew:         durationEnv("SANDBOX_CONTROLLER_CLOCK_SKEW", 5*time.Minute),
		RequestTimeout:    durationEnv("SANDBOX_CONTROLLER_REQUEST_TIMEOUT", 10*time.Second),
		ShutdownTimeout:   durationEnv("SANDBOX_CONTROLLER_SHUTDOWN_TIMEOUT", 15*time.Second),
		MaxRequestBytes:   int64Env("SANDBOX_CONTROLLER_MAX_REQUEST_BYTES", 1<<20),
		MaxConcurrent:     intEnv("SANDBOX_CONTROLLER_MAX_CONCURRENT", 16),
		SandboxNamespace:  env("SANDBOX_CONTROLLER_NAMESPACE", "kubeoncall-sandbox"),
		Tools:             defaultTools(),
		JobCeiling: jobs.Limits{
			CPUMilli:       1000,
			MemoryMiB:      1024,
			EphemeralMiB:   2048,
			TimeoutSeconds: 300,
			TTLSeconds:     86400,
		},
		LogLimitBytes: int64Env("SANDBOX_CONTROLLER_LOG_LIMIT_BYTES", 2*1024*1024),
	}
	if config.BackendHMACSecret == "" || config.MaxRequestBytes < 1024 || config.MaxConcurrent < 1 {
		return Config{}, fmt.Errorf("backend HMAC secret, request limit and concurrency must be configured safely")
	}
	return config, nil
}

// defaultTools mirrors the versioned Backend fixed-diagnostic catalog. The Controller owns only
// these exact ID/version/image tuples so a signed request cannot select an arbitrary image.
func defaultTools() map[string]jobs.Tool {
	return map[string]jobs.Tool{
		"log-pattern-analysis:v1": {
			ID:         "log-pattern-analysis",
			Version:    "v1",
			Image:      "registry.kubeoncall.io/sandbox/log-pattern-analysis@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			Entrypoint: []string{"/tool"},
		},
		"kubernetes-consistency:v1": {ID: "kubernetes-consistency", Version: "v1", Image: "registry.kubeoncall.io/sandbox/kubernetes-consistency@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", Entrypoint: []string{"/tool"}},
		"configuration-diff:v1":     {ID: "configuration-diff", Version: "v1", Image: "registry.kubeoncall.io/sandbox/configuration-diff@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", Entrypoint: []string{"/tool"}},
		"generated-python:v1": {
			ID:             "generated-python",
			Version:        "v1",
			Image:          "registry.kubeoncall.io/sandbox/generated-python@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
			Entrypoint:     []string{"/usr/local/bin/koc-run-python"},
			Runtime:        jobs.RuntimeGeneratedPython,
			MaxProcesses:   32,
			OutputMaxBytes: 1024 * 1024,
		},
		"generated-posix-shell:v1": {
			ID:             "generated-posix-shell",
			Version:        "v1",
			Image:          "registry.kubeoncall.io/sandbox/generated-posix-shell@sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
			Entrypoint:     []string{"/usr/local/bin/koc-run-posix-shell"},
			Runtime:        jobs.RuntimeGeneratedPOSIX,
			MaxProcesses:   32,
			OutputMaxBytes: 1024 * 1024,
		},
		"manifest-validation:v1": {
			ID:         "manifest-validation",
			Version:    "v1",
			Image:      "registry.kubeoncall.io/sandbox/manifest-validation@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
			Entrypoint: []string{"/usr/local/bin/koc-validate-manifest"},
			Runtime:    jobs.RuntimeFixedDiagnostic,
		},
	}
}

type Server struct {
	config        Config
	authenticator *authenticator
	semaphore     chan struct{}
	mux           *http.ServeMux
	manager       LifecycleManager
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
	return NewServerWithManager(config, nil)
}

// NewServerWithManager wires a Kubernetes lifecycle manager. When manager is nil the lifecycle
// endpoints report not-ready, preserving the scaffold's safe default from SBX-07.
func NewServerWithManager(config Config, manager LifecycleManager) *Server {
	server := &Server{config: config, authenticator: newAuthenticator(config), semaphore: make(chan struct{}, config.MaxConcurrent), mux: http.NewServeMux(), manager: manager}
	server.mux.HandleFunc("GET /healthz", server.health)
	server.mux.HandleFunc("POST /internal/v1/runs", server.acceptRun)
	server.mux.HandleFunc("GET /internal/v1/runs/{runId}", server.getRun)
	server.mux.HandleFunc("DELETE /internal/v1/runs/{runId}", server.cancelRun)
	server.mux.HandleFunc("GET /internal/v1/runs/{runId}/logs", server.getRunLogs)
	return server
}

func (server *Server) Handler() http.Handler { return server.requestID(server.limit(server.mux)) }

func (server *Server) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]any{"status": "ok"})
}

func (server *Server) acceptRun(writer http.ResponseWriter, request *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(writer, request.Body, server.config.MaxRequestBytes))
	if err != nil {
		writeError(writer, http.StatusRequestEntityTooLarge, "REQUEST_TOO_LARGE", "request body exceeds configured limit")
		return
	}
	if err := server.authenticator.verify(request, body); err != nil {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", err.Error())
		return
	}
	if server.manager == nil {
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
	status, err := server.manager.EnsureJob(request.Context(), jobs.Request{
		RunID:            payload.RunID,
		ToolID:           payload.ToolID,
		ToolVersion:      payload.ToolVersion,
		InputArtifactURI: payload.InputArtifactURI,
		Labels:           payload.Labels,
	}, expiresAt)
	server.writeLifecycleResult(writer, status, err)
}

func (server *Server) getRun(writer http.ResponseWriter, request *http.Request) {
	runID := request.PathValue("runId")
	if !server.authenticateGet(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "signature verification failed")
		return
	}
	if server.manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	status, err := server.manager.Status(request.Context(), runID)
	server.writeLifecycleResult(writer, status, err)
}

func (server *Server) cancelRun(writer http.ResponseWriter, request *http.Request) {
	runID := request.PathValue("runId")
	if !server.authenticateGet(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "signature verification failed")
		return
	}
	if server.manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	status, err := server.manager.Cancel(request.Context(), runID)
	server.writeLifecycleResult(writer, status, err)
}

// getRunLogs collects the normalized result — exit code, failure reason and redacted, size-bounded
// logs — for a run. The controller never streams raw pod logs; logs are truncated and scrubbed of
// secret-bearing patterns before leaving the cluster.
func (server *Server) getRunLogs(writer http.ResponseWriter, request *http.Request) {
	runID := request.PathValue("runId")
	if !server.authenticateGet(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "signature verification failed")
		return
	}
	if server.manager == nil {
		writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
		return
	}
	result, err := server.manager.Collect(request.Context(), runID, server.config.LogLimitBytes)
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
func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
func durationEnv(name string, fallback time.Duration) time.Duration {
	value, err := time.ParseDuration(os.Getenv(name))
	if os.Getenv(name) == "" || err != nil {
		return fallback
	}
	return value
}
func intEnv(name string, fallback int) int {
	value, err := strconv.Atoi(os.Getenv(name))
	if err != nil {
		return fallback
	}
	return value
}
func int64Env(name string, fallback int64) int64 {
	value, err := strconv.ParseInt(os.Getenv(name), 10, 64)
	if err != nil {
		return fallback
	}
	return value
}
