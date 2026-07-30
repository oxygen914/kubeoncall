package kubetooladapter

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config defines the fail-closed boundary for the read-only Kubernetes adapter.
type Config struct {
	ListenAddress   string
	BearerToken     string
	ClusterID       string
	Namespaces      map[string]struct{}
	RequestTimeout  time.Duration
	ShutdownTimeout time.Duration
	MaxRequestBytes int64
	MaxConcurrent   int
	MaxEvents       int
	MaxPods         int
	MaxLogLines     int64
	MaxLogBytes     int64
}

// LoadConfigFromEnv requires an authentication token, one stable cluster id and at least one
// namespace allowlist entry. Starting without any of them would turn a local development endpoint
// into an unscoped Kubernetes credential proxy.
func LoadConfigFromEnv() (Config, error) {
	config := Config{
		ListenAddress:   env("KUBERNETES_TOOL_ADAPTER_LISTEN_ADDRESS", ":8080"),
		BearerToken:     strings.TrimSpace(os.Getenv("KUBERNETES_TOOL_ADAPTER_BEARER_TOKEN")),
		ClusterID:       strings.TrimSpace(os.Getenv("KUBERNETES_TOOL_ADAPTER_CLUSTER_ID")),
		Namespaces:      csvSet(os.Getenv("KUBERNETES_TOOL_ADAPTER_ALLOWED_NAMESPACES")),
		RequestTimeout:  durationEnv("KUBERNETES_TOOL_ADAPTER_REQUEST_TIMEOUT", 10*time.Second),
		ShutdownTimeout: durationEnv("KUBERNETES_TOOL_ADAPTER_SHUTDOWN_TIMEOUT", 15*time.Second),
		MaxRequestBytes: int64Env("KUBERNETES_TOOL_ADAPTER_MAX_REQUEST_BYTES", 64*1024),
		MaxConcurrent:   intEnv("KUBERNETES_TOOL_ADAPTER_MAX_CONCURRENT", 8),
		MaxEvents:       intEnv("KUBERNETES_TOOL_ADAPTER_MAX_EVENTS", 200),
		MaxPods:         intEnv("KUBERNETES_TOOL_ADAPTER_MAX_PODS", 20),
		MaxLogLines:     int64Env("KUBERNETES_TOOL_ADAPTER_MAX_LOG_LINES", 500),
		MaxLogBytes:     int64Env("KUBERNETES_TOOL_ADAPTER_MAX_LOG_BYTES", 256*1024),
	}
	if len(config.BearerToken) < 32 {
		return Config{}, fmt.Errorf("adapter bearer token must contain at least 32 characters")
	}
	if config.ClusterID == "" {
		return Config{}, fmt.Errorf("adapter cluster id is required")
	}
	if len(config.Namespaces) == 0 {
		return Config{}, fmt.Errorf("at least one allowed namespace is required")
	}
	if config.MaxRequestBytes < 1024 || config.MaxConcurrent < 1 {
		return Config{}, fmt.Errorf("request size and concurrency limits must be positive")
	}
	if config.MaxEvents < 1 || config.MaxPods < 1 || config.MaxLogLines < 1 || config.MaxLogBytes < 1024 {
		return Config{}, fmt.Errorf("evidence collection limits must be positive")
	}
	return config, nil
}

func (config Config) namespaceAllowed(namespace string) bool {
	_, allowed := config.Namespaces[strings.TrimSpace(namespace)]
	return allowed
}

func csvSet(value string) map[string]struct{} {
	result := make(map[string]struct{})
	for _, item := range strings.Split(value, ",") {
		item = strings.TrimSpace(item)
		if item != "" {
			result[item] = struct{}{}
		}
	}
	return result
}

func env(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func durationEnv(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

func intEnv(name string, fallback int) int {
	value, err := strconv.Atoi(strings.TrimSpace(os.Getenv(name)))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

func int64Env(name string, fallback int64) int64 {
	value, err := strconv.ParseInt(strings.TrimSpace(os.Getenv(name)), 10, 64)
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}
