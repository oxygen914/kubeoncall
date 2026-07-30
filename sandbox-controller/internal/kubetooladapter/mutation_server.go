package kubetooladapter

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
)

type MutationServer struct {
	config    MutationConfig
	mutator   Mutator
	mux       *http.ServeMux
	semaphore chan struct{}
}

func NewMutationServer(config MutationConfig, mutator Mutator) *MutationServer {
	server := &MutationServer{
		config:    config,
		mutator:   mutator,
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

func (server *MutationServer) Handler() http.Handler {
	return http.TimeoutHandler(
		server.mux,
		server.config.RequestTimeout,
		`{"errorType":"Timeout","errorMessage":"request timed out"}`,
	)
}

func (server *MutationServer) health(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, map[string]any{"status": "ok", "mode": "governed-mutation"})
}

func (server *MutationServer) ready(writer http.ResponseWriter, request *http.Request) {
	if server.mutator == nil {
		writeError(writer, http.StatusServiceUnavailable, "CLIENT_UNAVAILABLE", "Kubernetes client is unavailable")
		return
	}
	if err := server.mutator.Ready(request.Context()); err != nil {
		writeError(
			writer,
			http.StatusServiceUnavailable,
			"MUTATION_ADAPTER_UNAVAILABLE",
			"Kubernetes API or operation ledger is unavailable",
		)
		return
	}
	writeJSON(writer, http.StatusOK, map[string]any{
		"status":  "ready",
		"cluster": server.config.ClusterID,
		"mode":    "governed-mutation",
	})
}

func (server *MutationServer) execute(writer http.ResponseWriter, request *http.Request) {
	if !authenticatedBearer(request, server.config.BearerToken) {
		writeError(writer, http.StatusUnauthorized, "UNAUTHENTICATED", "bearer token is missing or invalid")
		return
	}
	if contentType := request.Header.Get("Content-Type"); !strings.HasPrefix(
		strings.ToLower(contentType),
		"application/json",
	) {
		writeError(
			writer,
			http.StatusUnsupportedMediaType,
			"UNSUPPORTED_MEDIA_TYPE",
			"Content-Type must be application/json",
		)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(writer, request.Body, server.config.MaxRequestBytes))
	if err != nil {
		writeError(
			writer,
			http.StatusRequestEntityTooLarge,
			"REQUEST_TOO_LARGE",
			"request body exceeds configured limit",
		)
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
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(writer, http.StatusBadRequest, "INVALID_PAYLOAD", "request body must contain one JSON object")
		return
	}
	if envelope.Executor != "kubernetes" {
		writeError(writer, http.StatusBadRequest, "INVALID_EXECUTOR", "executor must be kubernetes")
		return
	}
	if envelope.Parameters == nil {
		envelope.Parameters = Parameters{}
	}
	if err := server.authorize(envelope.Action, envelope.Parameters); err != nil {
		server.writeActionError(writer, err)
		return
	}
	result, err := server.mutator.Execute(request.Context(), envelope.Action, envelope.Parameters)
	if err != nil {
		server.writeActionError(writer, err)
		return
	}
	writeJSON(writer, http.StatusOK, result)
}

func (server *MutationServer) authorize(action string, parameters Parameters) error {
	if !server.config.actionAllowed(action) {
		return &APIError{
			Status:  http.StatusForbidden,
			Code:    "ACTION_FORBIDDEN",
			Message: "requested mutation action is outside the adapter allowlist",
		}
	}
	if requested := text(parameters, "cluster"); requested != "" && requested != server.config.ClusterID {
		return &APIError{
			Status:  http.StatusForbidden,
			Code:    "CLUSTER_FORBIDDEN",
			Message: "requested cluster is outside the mutation adapter scope",
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
			Message: "requested namespace is outside the mutation allowlist",
		}
	}
	return nil
}

func (server *MutationServer) writeActionError(writer http.ResponseWriter, err error) {
	var apiError *APIError
	if errors.As(err, &apiError) {
		writeError(writer, apiError.Status, apiError.Code, apiError.Message)
		return
	}
	writeError(writer, http.StatusBadGateway, "KUBERNETES_MUTATION_ERROR", "Kubernetes mutation failed")
}

func (server *MutationServer) limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		select {
		case server.semaphore <- struct{}{}:
			defer func() { <-server.semaphore }()
			next.ServeHTTP(writer, request)
		default:
			writeError(
				writer,
				http.StatusTooManyRequests,
				"CONCURRENCY_LIMITED",
				"mutation adapter is at concurrency capacity",
			)
		}
	})
}

func authenticatedBearer(request *http.Request, expected string) bool {
	authorization := strings.Fields(request.Header.Get("Authorization"))
	if len(authorization) != 2 || !strings.EqualFold(authorization[0], "Bearer") {
		return false
	}
	provided := authorization[1]
	if provided == "" || len(provided) != len(expected) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), []byte(expected)) == 1
}
