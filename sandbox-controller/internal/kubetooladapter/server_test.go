package kubetooladapter

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type fakeReader struct {
	action     string
	parameters Parameters
}

func (reader *fakeReader) Ready(context.Context) error { return nil }

func (reader *fakeReader) QueryEvents(_ context.Context, parameters Parameters) (any, error) {
	reader.action, reader.parameters = "queryEvents", parameters
	return map[string]any{"items": []any{}}, nil
}

func (reader *fakeReader) QueryPodLogs(_ context.Context, parameters Parameters) (any, error) {
	reader.action, reader.parameters = "queryPodLogs", parameters
	return map[string]any{"items": []any{}}, nil
}

func (reader *fakeReader) DescribeResource(_ context.Context, parameters Parameters) (any, error) {
	reader.action, reader.parameters = "describeResource", parameters
	return map[string]any{"summary": "ready"}, nil
}

func (reader *fakeReader) DescribeWorkload(_ context.Context, parameters Parameters) (any, error) {
	reader.action, reader.parameters = "describeWorkload", parameters
	return map[string]any{"summary": "ready"}, nil
}

func (reader *fakeReader) GetPods(_ context.Context, parameters Parameters) (any, error) {
	reader.action, reader.parameters = "getPods", parameters
	return map[string]any{"items": []any{}}, nil
}

func TestServerRequiresAuthenticationAndAllowlistedScope(t *testing.T) {
	reader := &fakeReader{}
	server := NewServer(testConfig(), reader)
	body := toolRequest("queryEvents", Parameters{"cluster": "local", "namespace": "kubeoncall-system"})

	unauthenticated := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
	unauthenticated.Header.Set("Content-Type", "application/json")
	unauthenticatedResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(unauthenticatedResponse, unauthenticated)
	if unauthenticatedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", unauthenticatedResponse.Code)
	}

	rawToken := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
	rawToken.Header.Set("Content-Type", "application/json")
	rawToken.Header.Set("Authorization", "0123456789abcdef0123456789abcdef")
	rawTokenResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(rawTokenResponse, rawToken)
	if rawTokenResponse.Code != http.StatusUnauthorized {
		t.Fatalf("expected raw token without Bearer scheme to return 401, got %d", rawTokenResponse.Code)
	}

	request := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer 0123456789abcdef0123456789abcdef")
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", response.Code, response.Body.String())
	}
	if reader.action != "queryEvents" || text(reader.parameters, "namespace") != "kubeoncall-system" {
		t.Fatalf("unexpected reader call: action=%s parameters=%v", reader.action, reader.parameters)
	}
}

func TestServerRejectsMutationAndOutsideNamespace(t *testing.T) {
	reader := &fakeReader{}
	server := NewServer(testConfig(), reader)

	for name, testCase := range map[string]struct {
		action, namespace string
		expected          int
	}{
		"mutation":          {"rolloutRestart", "kubeoncall-system", http.StatusForbidden},
		"outside namespace": {"queryEvents", "default", http.StatusForbidden},
	} {
		t.Run(name, func(t *testing.T) {
			body := toolRequest(
				testCase.action,
				Parameters{"cluster": "local", "namespace": testCase.namespace},
			)
			request := httptest.NewRequest(http.MethodPost, "/api/tools/kubernetes", bytes.NewReader(body))
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("Authorization", "Bearer 0123456789abcdef0123456789abcdef")
			response := httptest.NewRecorder()
			server.Handler().ServeHTTP(response, request)
			if response.Code != testCase.expected {
				t.Fatalf("expected %d, got %d: %s", testCase.expected, response.Code, response.Body.String())
			}
		})
	}
}

func TestServerKeepsHealthProbeAvailableWhenToolConcurrencyIsSaturated(t *testing.T) {
	server := NewServer(testConfig(), &fakeReader{})
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

func TestLoadConfigFailsClosed(t *testing.T) {
	t.Setenv("KUBERNETES_TOOL_ADAPTER_BEARER_TOKEN", "0123456789abcdef0123456789abcdef")
	t.Setenv("KUBERNETES_TOOL_ADAPTER_CLUSTER_ID", "local")
	t.Setenv("KUBERNETES_TOOL_ADAPTER_ALLOWED_NAMESPACES", "kubeoncall-system, default")

	config, err := LoadConfigFromEnv()
	if err != nil {
		t.Fatalf("expected valid config: %v", err)
	}
	if !config.namespaceAllowed("kubeoncall-system") || config.namespaceAllowed("kube-system") {
		t.Fatalf("unexpected namespace allowlist: %v", config.Namespaces)
	}

	t.Setenv("KUBERNETES_TOOL_ADAPTER_BEARER_TOKEN", "")
	if _, err := LoadConfigFromEnv(); err == nil {
		t.Fatal("expected missing bearer token to fail")
	}
}

func testConfig() Config {
	return Config{
		BearerToken:     "0123456789abcdef0123456789abcdef",
		ClusterID:       "local",
		Namespaces:      map[string]struct{}{"kubeoncall-system": {}},
		RequestTimeout:  time.Second,
		ShutdownTimeout: time.Second,
		MaxRequestBytes: 64 * 1024,
		MaxConcurrent:   2,
		MaxEvents:       20,
		MaxPods:         10,
		MaxLogLines:     100,
		MaxLogBytes:     64 * 1024,
	}
}

func toolRequest(action string, parameters Parameters) []byte {
	value, _ := json.Marshal(map[string]any{
		"executor":   "kubernetes",
		"action":     action,
		"parameters": parameters,
	})
	return value
}
