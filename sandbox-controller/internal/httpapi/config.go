package httpapi

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

// Config contains internal transport controls and the server-owned sandbox tool catalog.
type Config struct {
	ListenAddress     string
	BackendKeyID      string
	BackendHMACSecret string
	ClockSkew         time.Duration
	RequestTimeout    time.Duration
	ShutdownTimeout   time.Duration
	MaxRequestBytes   int64
	MaxConcurrent     int
	SandboxNamespace  string
	Tools             map[string]jobs.Tool
	JobCeiling        jobs.Limits
	LogLimitBytes     int64
	// SimulationKubeconfigPath is an explicit credential for an independent validation cluster. An
	// empty path disables remediation simulation; it must never fall back to this controller's
	// in-cluster production-facing credentials.
	SimulationKubeconfigPath  string
	SimulationClusterID       string
	SimulationNamespacePrefix string
}

func LoadConfigFromEnv() (Config, error) {
	tools := defaultTools()
	if catalogPath := strings.TrimSpace(os.Getenv("SANDBOX_CONTROLLER_TOOL_CATALOG_PATH")); catalogPath != "" {
		var err error
		tools, err = loadToolCatalog(catalogPath)
		if err != nil {
			return Config{}, fmt.Errorf("load sandbox tool catalog: %w", err)
		}
	}
	config := Config{
		ListenAddress:             env("SANDBOX_CONTROLLER_LISTEN_ADDRESS", ":8088"),
		BackendKeyID:              env("SANDBOX_CONTROLLER_BACKEND_KEY_ID", "backend"),
		BackendHMACSecret:         os.Getenv("SANDBOX_CONTROLLER_BACKEND_HMAC_SECRET"),
		ClockSkew:                 durationEnv("SANDBOX_CONTROLLER_CLOCK_SKEW", 5*time.Minute),
		RequestTimeout:            durationEnv("SANDBOX_CONTROLLER_REQUEST_TIMEOUT", 10*time.Second),
		ShutdownTimeout:           durationEnv("SANDBOX_CONTROLLER_SHUTDOWN_TIMEOUT", 15*time.Second),
		MaxRequestBytes:           int64Env("SANDBOX_CONTROLLER_MAX_REQUEST_BYTES", 1<<20),
		MaxConcurrent:             intEnv("SANDBOX_CONTROLLER_MAX_CONCURRENT", 16),
		SandboxNamespace:          env("SANDBOX_CONTROLLER_NAMESPACE", "kubeoncall-sandbox"),
		Tools:                     tools,
		JobCeiling:                defaultControllerCeiling(),
		LogLimitBytes:             int64Env("SANDBOX_CONTROLLER_LOG_LIMIT_BYTES", 2*1024*1024),
		SimulationKubeconfigPath:  os.Getenv("SANDBOX_CONTROLLER_SIMULATION_KUBECONFIG"),
		SimulationClusterID:       env("SANDBOX_CONTROLLER_SIMULATION_CLUSTER_ID", ""),
		SimulationNamespacePrefix: env("SANDBOX_CONTROLLER_SIMULATION_NAMESPACE_PREFIX", "koc-sim-"),
	}
	if config.BackendHMACSecret == "" || config.MaxRequestBytes < 1024 || config.MaxConcurrent < 1 {
		return Config{}, fmt.Errorf("backend HMAC secret, request limit and concurrency must be configured safely")
	}
	return config, nil
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
