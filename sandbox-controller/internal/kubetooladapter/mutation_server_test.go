package kubetooladapter

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type fakeMutator struct {
	action     string
	parameters Parameters
}

func (mutator *fakeMutator) Ready(context.Context) error { return nil }

func (mutator *fakeMutator) Execute(
	_ context.Context,
	action string,
	parameters Parameters,
) (any, error) {
	mutator.action, mutator.parameters = action, parameters
	return map[string]any{"operationId": text(parameters, "operationId"), "applied": true}, nil
}

func TestMutationServerUsesIndependentAuthenticationAndActionScope(t *testing.T) {
	mutator := &fakeMutator{}
	server := NewMutationServer(testMutationConfig(), mutator)
	body := toolRequest("scaleWorkload", Parameters{
		"cluster":             "local",
		"namespace":           "kubeoncall-system",
		"target":              "api",
		"replicas":            2,
		"operationId":         "exec-1:task-1:kubernetes.scaleWorkload",
		"expectedResourceUid": "uid-1",
		"expectedGeneration":  1,
	})

	readTokenRequest := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
	readTokenRequest.Header.Set("Content-Type", "application/json")
	readTokenRequest.Header.Set("Authorization", "Bearer 0123456789abcdef0123456789abcdef")
	readTokenResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(readTokenResponse, readTokenRequest)
	if readTokenResponse.Code != http.StatusUnauthorized {
		t.Fatalf("expected independent token to reject read credential, got %d", readTokenResponse.Code)
	}

	request := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer fedcba9876543210fedcba9876543210")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", response.Code, response.Body.String())
	}
	if mutator.action != "scaleWorkload" || text(mutator.parameters, "target") != "api" {
		t.Fatalf("unexpected mutation dispatch: action=%s parameters=%v", mutator.action, mutator.parameters)
	}
}

func TestMutationServerRejectsUnlistedActionAndNamespace(t *testing.T) {
	server := NewMutationServer(testMutationConfig(), &fakeMutator{})
	for name, testCase := range map[string]struct {
		action    string
		namespace string
		expected  int
	}{
		"action":    {"patchConfig", "kubeoncall-system", http.StatusForbidden},
		"namespace": {"scaleWorkload", "default", http.StatusForbidden},
	} {
		t.Run(name, func(t *testing.T) {
			body := toolRequest(testCase.action, Parameters{
				"cluster":     "local",
				"namespace":   testCase.namespace,
				"operationId": "exec-1:task-1:kubernetes.scaleWorkload",
			})
			request := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("Authorization", "Bearer fedcba9876543210fedcba9876543210")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != testCase.expected {
				t.Fatalf("expected %d, got %d: %s", testCase.expected, response.Code, response.Body.String())
			}
		})
	}
}

func TestMutationServerRejectsTrailingJsonDocument(t *testing.T) {
	server := NewMutationServer(testMutationConfig(), &fakeMutator{})
	body := append(toolRequest("scaleWorkload", Parameters{
		"cluster":             "local",
		"namespace":           "kubeoncall-system",
		"target":              "api",
		"replicas":            2,
		"operationId":         "exec-1:task-1:kubernetes.scaleWorkload",
		"expectedResourceUid": "uid-1",
		"expectedGeneration":  1,
	}), []byte("\n{}")...)
	request := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer fedcba9876543210fedcba9876543210")
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", response.Code, response.Body.String())
	}
}

func TestMutationServerKeepsHealthProbeAvailableWhenToolConcurrencyIsSaturated(t *testing.T) {
	server := NewMutationServer(testMutationConfig(), &fakeMutator{})
	for range cap(server.semaphore) {
		server.semaphore <- struct{}{}
	}
	defer func() {
		for range cap(server.semaphore) {
			<-server.semaphore
		}
	}()
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	response := httptest.NewRecorder()

	server.Handler().ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("expected health probe to bypass tool concurrency limit, got %d", response.Code)
	}
}

func TestLoadMutationConfigFailsClosed(t *testing.T) {
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_BEARER_TOKEN", "fedcba9876543210fedcba9876543210")
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_CLUSTER_ID", "local")
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_NAMESPACES", "kubeoncall-system")
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_LEDGER_NAMESPACE", "kubeoncall-system")
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_ACTIONS", "scaleWorkload")

	config, err := LoadMutationConfigFromEnv()
	if err != nil {
		t.Fatalf("expected valid mutation config: %v", err)
	}
	if !config.actionAllowed("scaleWorkload") || config.actionAllowed("patchConfig") {
		t.Fatalf("unexpected action allowlist: %v", config.AllowedActions)
	}

	t.Setenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_ACTIONS", "")
	if _, err := LoadMutationConfigFromEnv(); err == nil {
		t.Fatal("expected empty action allowlist to fail")
	}

	t.Setenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_ACTIONS", "patchConfig")
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_CONFIG_KEYS", "")
	if _, err := LoadMutationConfigFromEnv(); err == nil {
		t.Fatal("expected patchConfig without allowed keys to fail")
	}

	t.Setenv("KUBERNETES_MUTATION_ADAPTER_ALLOWED_ACTIONS", "scaleWorkload")
	t.Setenv("KUBERNETES_MUTATION_ADAPTER_MAX_REPLICAS", "0")
	if _, err := LoadMutationConfigFromEnv(); err == nil {
		t.Fatal("expected an invalid safety limit to fail instead of using a default")
	}
}

func testMutationConfig() MutationConfig {
	return MutationConfig{
		ListenAddress:       ":8080",
		BearerToken:         "fedcba9876543210fedcba9876543210",
		ClusterID:           "local",
		Namespaces:          map[string]struct{}{"kubeoncall-system": {}},
		LedgerNamespace:     "kubeoncall-system",
		AllowedActions:      map[string]struct{}{"scaleWorkload": {}, "rolloutRestart": {}, "rolloutUndo": {}},
		AllowedConfigKeys:   map[string]struct{}{"REQUEST_TIMEOUT": {}},
		RequestTimeout:      time.Second,
		ShutdownTimeout:     time.Second,
		MaxRequestBytes:     64 * 1024,
		MaxConcurrent:       2,
		MaxReplicas:         10,
		MaxConfigValueBytes: 128,
	}
}
