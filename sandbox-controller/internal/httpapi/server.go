package httpapi

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"time"
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
	}
	if config.BackendHMACSecret == "" || config.MaxRequestBytes < 1024 || config.MaxConcurrent < 1 {
		return Config{}, fmt.Errorf("backend HMAC secret, request limit and concurrency must be configured safely")
	}
	return config, nil
}

type Server struct {
	config        Config
	authenticator *authenticator
	semaphore     chan struct{}
	mux           *http.ServeMux
}

func NewServer(config Config) *Server {
	server := &Server{config: config, authenticator: newAuthenticator(config), semaphore: make(chan struct{}, config.MaxConcurrent), mux: http.NewServeMux()}
	server.mux.HandleFunc("GET /healthz", server.health)
	server.mux.HandleFunc("POST /internal/v1/runs", server.acceptRun)
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
	// SBX-08/09 own Job validation and lifecycle. Accepting no work here prevents the scaffold from
	// looking operational before the hardened runtime path exists.
	writeError(writer, http.StatusNotImplemented, "CONTROLLER_NOT_READY", "sandbox job lifecycle is not enabled")
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
