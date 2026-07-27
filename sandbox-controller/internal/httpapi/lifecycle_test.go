package httpapi

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/kubeoncall/sandbox-controller/internal/jobs"
)

// fakeManager is a recording LifecycleManager for HTTP-level lifecycle tests.
type fakeManager struct {
	created []jobs.Request
	status  LifecycleStatus
	result  LifecycleResult
	err     error
	cancels []string
}

func (fake *fakeManager) EnsureJob(_ context.Context, request jobs.Request, _ time.Time) (LifecycleStatus, error) {
	fake.created = append(fake.created, request)
	return fake.status, fake.err
}

func (fake *fakeManager) Status(_ context.Context, _ string) (LifecycleStatus, error) {
	return fake.status, fake.err
}

func (fake *fakeManager) Cancel(_ context.Context, runID string) (LifecycleStatus, error) {
	fake.cancels = append(fake.cancels, runID)
	return fake.status, fake.err
}

func (fake *fakeManager) Collect(_ context.Context, _ string, _ int64) (LifecycleResult, error) {
	return fake.result, fake.err
}

func signedLifecycle(config Config, method, path string, body []byte, nonce string) *http.Request {
	request := httptest.NewRequest(method, path, strings.NewReader(string(body)))
	timestamp := strconvFormat(time.Now().Unix())
	request.Header.Set(headerKeyID, config.BackendKeyID)
	request.Header.Set(headerTimestamp, timestamp)
	request.Header.Set(headerNonce, nonce)
	request.Header.Set(headerSignature, hex.EncodeToString(sign([]byte(config.BackendHMACSecret), method, path, timestamp, nonce, body)))
	return request
}

func TestLifecycleEndpointsRejectUnsignedRequests(t *testing.T) {
	config := testConfig()
	manager := &fakeManager{status: LifecycleStatus{RunID: "sbx_a1b2", Phase: "PENDING", Exists: true}}
	server := NewServerWithManager(config, manager)

	for _, target := range []struct {
		method, path string
	}{
		{http.MethodPost, "/internal/v1/runs"},
		{http.MethodGet, "/internal/v1/runs/sbx_a1b2"},
		{http.MethodDelete, "/internal/v1/runs/sbx_a1b2"},
	} {
		response := httptest.NewRecorder()
		server.Handler().ServeHTTP(response, httptest.NewRequest(target.method, target.path, nil))
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("%s %s unsigned status = %d", target.method, target.path, response.Code)
		}
	}
}

func TestPostRunCreatesJobThroughManager(t *testing.T) {
	config := testConfig()
	manager := &fakeManager{status: LifecycleStatus{RunID: "sbx_a1b2", Phase: "PENDING", Exists: true}}
	server := NewServerWithManager(config, manager)

	body := []byte(`{"runId":"sbx_a1b2","toolId":"pod-inspect","toolVersion":"v1","inputArtifactUri":"minio://sandbox/sbx_a1b2/inputs/request.json"}`)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, signedLifecycle(config, http.MethodPost, "/internal/v1/runs", body, "nonce-lifecycle-0001"))
	if response.Code != http.StatusOK {
		t.Fatalf("create status = %d body=%s", response.Code, response.Body.String())
	}
	if len(manager.created) != 1 || manager.created[0].RunID != "sbx_a1b2" {
		t.Fatalf("manager received = %+v", manager.created)
	}
	if !strings.Contains(response.Body.String(), `"runId":"sbx_a1b2"`) || strings.Contains(response.Body.String(), `"RunID"`) {
		t.Fatalf("lifecycle response does not use lower-camel contract: %s", response.Body.String())
	}
	var result LifecycleStatus
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if result.Phase != "PENDING" || !result.Exists {
		t.Fatalf("result = %+v", result)
	}
}

func TestGetAndCancelRunDelegateToManager(t *testing.T) {
	config := testConfig()
	manager := &fakeManager{status: LifecycleStatus{RunID: "sbx_a1b2", Phase: "RUNNING", Exists: true}}
	server := NewServerWithManager(config, manager)

	getResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(getResponse, signedLifecycle(config, http.MethodGet, "/internal/v1/runs/sbx_a1b2", nil, "nonce-get-0000000001"))
	if getResponse.Code != http.StatusOK {
		t.Fatalf("get status = %d", getResponse.Code)
	}

	manager.status = LifecycleStatus{RunID: "sbx_a1b2", Phase: "CANCELLED", Exists: true}
	cancelResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(cancelResponse, signedLifecycle(config, http.MethodDelete, "/internal/v1/runs/sbx_a1b2", nil, "nonce-cancel-0001"))
	if cancelResponse.Code != http.StatusOK {
		t.Fatalf("cancel status = %d", cancelResponse.Code)
	}
	if len(manager.cancels) != 1 || manager.cancels[0] != "sbx_a1b2" {
		t.Fatalf("cancels = %+v", manager.cancels)
	}
}

func TestNilManagerReportsNotReadyForLifecycle(t *testing.T) {
	config := testConfig()
	server := NewServer(config) // no manager wired

	body := []byte(`{"runId":"sbx_a1b2","toolId":"pod-inspect","toolVersion":"v1","inputArtifactUri":"minio://sandbox/sbx_a1b2/inputs/request.json"}`)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, signedLifecycle(config, http.MethodPost, "/internal/v1/runs", body, "nonce-notready-001"))
	if response.Code != http.StatusNotImplemented {
		t.Fatalf("nil manager create status = %d", response.Code)
	}
}

func TestLogsEndpointCollectsResultThroughManager(t *testing.T) {
	config := testConfig()
	manager := &fakeManager{result: LifecycleResult{RunID: "sbx_a1b2", Phase: "FAILED", Reason: "OOM", Logs: "killed by OOM\n[REDACTED]\n"}}
	server := NewServerWithManager(config, manager)

	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, signedLifecycle(config, http.MethodGet, "/internal/v1/runs/sbx_a1b2/logs", nil, "nonce-logs-00000001"))
	if response.Code != http.StatusOK {
		t.Fatalf("logs status = %d body=%s", response.Code, response.Body.String())
	}
	var result LifecycleResult
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if result.Phase != "FAILED" || result.Reason != "OOM" {
		t.Fatalf("result = %+v", result)
	}
}
