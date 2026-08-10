package kubetooladapter

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"
)

// Reader is the read-only Kubernetes capability surface exposed over HTTP.
type Reader interface {
	Ready(context.Context) error
	QueryEvents(context.Context, Parameters) (any, error)
	QueryPodLogs(context.Context, Parameters) (any, error)
	DescribeResource(context.Context, Parameters) (any, error)
	DescribeWorkload(context.Context, Parameters) (any, error)
	GetPods(context.Context, Parameters) (any, error)
	QueryMetricsContext(context.Context, Parameters) (any, error)
}

// Parameters preserves the backend's governed tool envelope without accepting arbitrary actions.
type Parameters map[string]any

// APIError carries a safe HTTP status and stable error type. Raw Kubernetes errors are never
// serialized to callers because they can contain internal endpoints and authorization details.
type APIError struct {
	Status  int
	Code    string
	Message string
}

func (err *APIError) Error() string { return err.Message }

type Server struct {
	config    Config
	reader    Reader
	mux       *http.ServeMux
	semaphore chan struct{}
}

func NewServer(config Config, reader Reader) *Server {
	server := &Server{
		config:    config,
		reader:    reader,
		mux:       http.NewServeMux(),
		semaphore: make(chan struct{}, config.MaxConcurrent),
	}
	server.mux.HandleFunc("GET /healthz", server.health)
	server.mux.HandleFunc("GET /readyz", server.ready)
	server.mux.Handle(
		"POST /api/tools/kubernetes",
		server.limit(http.HandlerFunc(server.execute)),
	)
	return server
}

func (server *Server) Handler() http.Handler {
	return http.TimeoutHandler(
		server.mux,
		server.config.RequestTimeout,
		`{"errorType":"Timeout","errorMessage":"request timed out"}`,
	)
}

func (server *Server) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]any{"status": "ok", "mode": "read-only"})
}

func (server *Server) ready(writer http.ResponseWriter, request *http.Request) {
	if server.reader == nil {
		writeError(writer, http.StatusServiceUnavailable, "CLIENT_UNAVAILABLE", "Kubernetes client is unavailable")
		return
	}
	if err := server.reader.Ready(request.Context()); err != nil {
		writeError(writer, http.StatusServiceUnavailable, "KUBERNETES_UNAVAILABLE", "Kubernetes API is unavailable")
		return
	}
	writeJSON(writer, http.StatusOK, map[string]any{"status": "ready", "cluster": server.config.ClusterID})
}

func (server *Server) execute(writer http.ResponseWriter, request *http.Request) {
	if !server.authenticated(request) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "bearer token is missing or invalid")
		return
	}
	if contentType := request.Header.Get("Content-Type"); !strings.HasPrefix(strings.ToLower(contentType), "application/json") {
		writeError(writer, http.StatusUnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json")
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(writer, request.Body, server.config.MaxRequestBytes))
	if err != nil {
		writeError(writer, http.StatusRequestEntityTooLarge, "REQUEST_TOO_LARGE", "request body exceeds configured limit")
		return
	}
	var envelope struct {
		Executor   string     `json:"executor"`
		Action     string     `json:"action"`
		Parameters Parameters `json:"parameters"`
	}
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&envelope); err != nil {
		writeError(writer, http.StatusBadRequest, "INVALID_PAYLOAD", "request body is not valid")
		return
	}
	if envelope.Executor != "kubernetes" {
		writeError(writer, http.StatusBadRequest, "INVALID_EXECUTOR", "executor must be kubernetes")
		return
	}
	if envelope.Parameters == nil {
		envelope.Parameters = Parameters{}
	}
	if err := server.authorizeScope(envelope.Parameters); err != nil {
		server.writeActionError(writer, err)
		return
	}

	var result any
	switch envelope.Action {
	case "queryEvents":
		result, err = server.reader.QueryEvents(request.Context(), envelope.Parameters)
	case "queryPodLogs", "queryLogs":
		result, err = server.reader.QueryPodLogs(request.Context(), envelope.Parameters)
	case "describeResource":
		result, err = server.reader.DescribeResource(request.Context(), envelope.Parameters)
	case "describeWorkload":
		result, err = server.reader.DescribeWorkload(request.Context(), envelope.Parameters)
	case "getPods":
		result, err = server.reader.GetPods(request.Context(), envelope.Parameters)
	case "queryMetricsContext":
		result, err = server.reader.QueryMetricsContext(request.Context(), envelope.Parameters)
	case "rolloutRestart", "rolloutUndo", "scaleWorkload", "patchConfig":
		err = &APIError{
			Status:  http.StatusForbidden,
			Code:    "READ_ONLY_MODE",
			Message: "mutating Kubernetes actions are disabled",
		}
	default:
		err = &APIError{
			Status:  http.StatusBadRequest,
			Code:    "UNSUPPORTED_ACTION",
			Message: "requested Kubernetes action is not supported",
		}
	}
	if err != nil {
		server.writeActionError(writer, err)
		return
	}
	writeJSON(writer, http.StatusOK, result)
}

func (server *Server) authorizeScope(parameters Parameters) error {
	if requested := text(parameters, "cluster"); requested != "" && requested != server.config.ClusterID {
		return &APIError{
			Status:  http.StatusForbidden,
			Code:    "CLUSTER_FORBIDDEN",
			Message: "requested cluster is outside the adapter allowlist",
		}
	}
	namespace := text(parameters, "namespace")
	if namespace == "" {
		return &APIError{
			Status:  http.StatusBadRequest,
			Code:    "NAMESPACE_REQUIRED",
			Message: "namespace is required",
		}
	}
	if !server.config.namespaceAllowed(namespace) {
		return &APIError{
			Status:  http.StatusForbidden,
			Code:    "NAMESPACE_FORBIDDEN",
			Message: "requested namespace is outside the adapter allowlist",
		}
	}
	return nil
}

func (server *Server) writeActionError(writer http.ResponseWriter, err error) {
	var apiError *APIError
	if errors.As(err, &apiError) {
		writeError(writer, apiError.Status, apiError.Code, apiError.Message)
		return
	}
	writeError(writer, http.StatusBadGateway, "KUBERNETES_ERROR", "Kubernetes read operation failed")
}

func (server *Server) authenticated(request *http.Request) bool {
	authorization := strings.Fields(request.Header.Get("Authorization"))
	if len(authorization) != 2 || !strings.EqualFold(authorization[0], "Bearer") {
		return false
	}
	provided := authorization[1]
	if provided == "" || len(provided) != len(server.config.BearerToken) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(server.config.BearerToken)) == 1
}

func (server *Server) limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		select {
		case server.semaphore <- struct{}{}:
			defer func() { <-server.semaphore }()
			next.ServeHTTP(writer, request)
		default:
			writeError(writer, http.StatusTooManyRequests, "CONCURRENCY_LIMITED", "adapter is at concurrency capacity")
		}
	})
}

func writeJSON(writer http.ResponseWriter, status int, value any) {
	writer.Header().Set("Content-Type", "application/json")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(value)
}

func writeError(writer http.ResponseWriter, status int, code, message string) {
	writeJSON(writer, status, map[string]any{"errorType": code, "errorMessage": message})
}

func text(parameters Parameters, keys ...string) string {
	for _, key := range keys {
		if value, exists := parameters[key]; exists && value != nil {
			result := strings.TrimSpace(strings.TrimSpace(toString(value)))
			if result != "" {
				return result
			}
		}
	}
	return ""
}

func toString(value any) string {
	switch typed := value.(type) {
	case string:
		return typed
	case json.Number:
		return typed.String()
	default:
		encoded, _ := json.Marshal(typed)
		return strings.Trim(string(encoded), `"`)
	}
}

func parseTime(parameters Parameters, key string) *time.Time {
	value := text(parameters, key)
	if value == "" {
		return nil
	}
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return nil
	}
	return &parsed
}
