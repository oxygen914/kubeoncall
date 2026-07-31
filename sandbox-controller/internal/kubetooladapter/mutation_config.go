package kubetooladapter

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

var supportedMutationActions = map[string]struct{}{
	"rolloutRestart": {},
	"rolloutUndo":    {},
	"scaleWorkload":  {},
	"patchConfig":    {},
}

// MutationConfig defines the independent, fail-closed boundary for governed Kubernetes changes.
// It deliberately does not reuse the read adapter token or action defaults.
type MutationConfig struct {
	ListenAddress         string
	BearerToken           string
	ClusterID             string
	Namespaces            map[string]struct{}
	LedgerNamespace       string
	AllowedActions        map[string]struct{}
	AllowedConfigKeys     map[string]struct{}
	LedgerRetention       time.Duration
	LedgerCleanupInterval time.Duration
	RequestTimeout        time.Duration
	ShutdownTimeout       time.Duration
	MaxRequestBytes       int64
	MaxConcurrent         int
	MaxReplicas           int
	MaxConfigValueBytes   int
}

// LoadMutationConfigFromEnv requires an explicit action allowlist. An empty action list cannot
// accidentally become "allow all", and patchConfig additionally requires an explicit key list.
func LoadMutationConfigFromEnv() (MutationConfig, error) {
	ledgerRetention, err := strictMutationDuration(
		"KUBERNETES_MUTATION_ADAPTER_LEDGER_RETENTION",
		30*24*time.Hour,
	)
	if err != nil {
		return MutationConfig{}, err
	}
	ledgerCleanupInterval, err := strictMutationDuration(
		"KUBERNETES_MUTATION_ADAPTER_LEDGER_CLEANUP_INTERVAL",
		time.Hour,
	)
	if err != nil {
		return MutationConfig{}, err
	}
	requestTimeout, err := strictMutationDuration(
		"KUBERNETES_MUTATION_ADAPTER_REQUEST_TIMEOUT",
		15*time.Second,
	)
	if err != nil {
		return MutationConfig{}, err
	}
	shutdownTimeout, err := strictMutationDuration(
		"KUBERNETES_MUTATION_ADAPTER_SHUTDOWN_TIMEOUT",
		15*time.Second,
	)
	if err != nil {
		return MutationConfig{}, err
	}
	maxRequestBytes, err := strictMutationInt64(
		"KUBERNETES_MUTATION_ADAPTER_MAX_REQUEST_BYTES",
		64*1024,
	)
	if err != nil {
		return MutationConfig{}, err
	}
	maxConcurrent, err := strictMutationInt("KUBERNETES_MUTATION_ADAPTER_MAX_CONCURRENT", 4)
	if err != nil {
		return MutationConfig{}, err
	}
	maxReplicas, err := strictMutationInt("KUBERNETES_MUTATION_ADAPTER_MAX_REPLICAS", 50)
	if err != nil {
		return MutationConfig{}, err
	}
	maxConfigValueBytes, err := strictMutationInt(
		"KUBERNETES_MUTATION_ADAPTER_MAX_CONFIG_VALUE_BYTES",
		1024,
	)
	if err != nil {
		return MutationConfig{}, err
	}
	config := MutationConfig{
		ListenAddress:         env("KUBERNETES_MUTATION_ADAPTER_LISTEN_ADDRESS", ":8080"),
		BearerToken:           strings.TrimSpace(os.Getenv("KUBERNETES_MUTATION_ADAPTER_BEARER_TOKEN")),
		ClusterID:             strings.TrimSpace(os.Getenv("KUBERNETES_MUTATION_ADAPTER_CLUSTER_ID")),
		Namespaces:            csvSet(os.Getenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_NAMESPACES")),
		LedgerNamespace:       strings.TrimSpace(os.Getenv("KUBERNETES_MUTATION_ADAPTER_LEDGER_NAMESPACE")),
		AllowedActions:        csvSet(os.Getenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_ACTIONS")),
		AllowedConfigKeys:     csvSet(os.Getenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_CONFIG_KEYS")),
		LedgerRetention:       ledgerRetention,
		LedgerCleanupInterval: ledgerCleanupInterval,
		RequestTimeout:        requestTimeout,
		ShutdownTimeout:       shutdownTimeout,
		MaxRequestBytes:       maxRequestBytes,
		MaxConcurrent:         maxConcurrent,
		MaxReplicas:           maxReplicas,
		MaxConfigValueBytes:   maxConfigValueBytes,
	}
	if len(config.BearerToken) < 32 {
		return MutationConfig{}, fmt.Errorf("mutation adapter bearer token must contain at least 32 characters")
	}
	if config.ClusterID == "" {
		return MutationConfig{}, fmt.Errorf("mutation adapter cluster id is required")
	}
	if len(config.Namespaces) == 0 {
		return MutationConfig{}, fmt.Errorf("at least one mutation namespace is required")
	}
	if config.LedgerNamespace == "" {
		return MutationConfig{}, fmt.Errorf("operation ledger namespace is required")
	}
	if len(config.AllowedActions) == 0 {
		return MutationConfig{}, fmt.Errorf("at least one mutation action must be explicitly allowed")
	}
	for action := range config.AllowedActions {
		if _, supported := supportedMutationActions[action]; !supported {
			return MutationConfig{}, fmt.Errorf("unsupported mutation action %q", action)
		}
	}
	if _, patchEnabled := config.AllowedActions["patchConfig"]; patchEnabled && len(config.AllowedConfigKeys) == 0 {
		return MutationConfig{}, fmt.Errorf("patchConfig requires at least one allowed config key")
	}
	if config.MaxRequestBytes < 1024 || config.MaxConcurrent < 1 || config.MaxReplicas < 1 {
		return MutationConfig{}, fmt.Errorf("request, concurrency and replica limits must be positive")
	}
	if config.MaxConfigValueBytes < 1 {
		return MutationConfig{}, fmt.Errorf("config value limit must be positive")
	}
	if config.LedgerRetention <= config.LedgerCleanupInterval {
		return MutationConfig{}, fmt.Errorf("operation ledger retention must exceed its cleanup interval")
	}
	return config, nil
}

func (config MutationConfig) namespaceAllowed(namespace string) bool {
	_, allowed := config.Namespaces[strings.TrimSpace(namespace)]
	return allowed
}

func (config MutationConfig) actionAllowed(action string) bool {
	_, allowed := config.AllowedActions[strings.TrimSpace(action)]
	return allowed
}

func (config MutationConfig) configKeyAllowed(key string) bool {
	_, allowed := config.AllowedConfigKeys[strings.TrimSpace(key)]
	return allowed
}

func strictMutationDuration(name string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive duration", name)
	}
	return parsed, nil
}

func strictMutationInt(name string, fallback int) (int, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", name)
	}
	return parsed, nil
}

func strictMutationInt64(name string, fallback int64) (int64, error) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", name)
	}
	return parsed, nil
}
