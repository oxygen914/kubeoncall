package httpapi

import (
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestInternalRunRejectsUnsignedAndAcceptsValidSignatureUntilLifecycleExists(t *testing.T) {
	config := testConfig()
	server := NewServer(config)
	unsigned := httptest.NewRequest(http.MethodPost, "/internal/v1/runs", strings.NewReader("{}"))
	unsignedResponse := httptest.NewRecorder()
	server.Handler().ServeHTTP(unsignedResponse, unsigned)
	if unsignedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unsigned status = %d", unsignedResponse.Code)
	}

	body := []byte("{}")
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/runs", strings.NewReader(string(body)))
	timestamp := strconvFormat(time.Now().Unix())
	request.Header.Set(headerKeyID, config.BackendKeyID)
	request.Header.Set(headerTimestamp, timestamp)
	request.Header.Set(headerNonce, "nonce-0123456789abcdef")
	request.Header.Set(headerSignature, hex.EncodeToString(sign([]byte(config.BackendHMACSecret), http.MethodPost, "/internal/v1/runs", timestamp, request.Header.Get(headerNonce), body)))
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusNotImplemented {
		t.Fatalf("signed status = %d", response.Code)
	}
}

func TestInternalRunRejectsReplayAndExpiredTimestamp(t *testing.T) {
	config := testConfig()
	server := NewServer(config)
	body := []byte("{}")
	request := signedRequest(config, body, time.Now().Unix(), "nonce-0123456789abcdef")
	first := httptest.NewRecorder()
	server.Handler().ServeHTTP(first, request)
	second := httptest.NewRecorder()
	server.Handler().ServeHTTP(second, signedRequest(config, body, time.Now().Unix(), "nonce-0123456789abcdef"))
	if second.Code != http.StatusUnauthorized {
		t.Fatalf("replay status = %d", second.Code)
	}
	expired := httptest.NewRecorder()
	server.Handler().ServeHTTP(expired, signedRequest(config, body, time.Now().Add(-10*time.Minute).Unix(), "nonce-abcdef0123456789"))
	if expired.Code != http.StatusUnauthorized {
		t.Fatalf("expired status = %d", expired.Code)
	}
}

func TestInternalRunRejectsOversizedRequestBeforeAuthentication(t *testing.T) {
	config := testConfig()
	server := NewServer(config)
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/runs", strings.NewReader(strings.Repeat("x", 1025)))
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized status = %d", response.Code)
	}
}

func testConfig() Config {
	return Config{ListenAddress: ":0", BackendKeyID: "backend", BackendHMACSecret: "test-secret", ClockSkew: time.Minute, RequestTimeout: time.Second, ShutdownTimeout: time.Second, MaxRequestBytes: 1024, MaxConcurrent: 1, LogLimitBytes: 1024}
}
func signedRequest(config Config, body []byte, unix int64, nonce string) *http.Request {
	request := httptest.NewRequest(http.MethodPost, "/internal/v1/runs", strings.NewReader(string(body)))
	timestamp := strconvFormat(unix)
	request.Header.Set(headerKeyID, config.BackendKeyID)
	request.Header.Set(headerTimestamp, timestamp)
	request.Header.Set(headerNonce, nonce)
	request.Header.Set(headerSignature, hex.EncodeToString(sign([]byte(config.BackendHMACSecret), http.MethodPost, "/internal/v1/runs", timestamp, nonce, body)))
	return request
}
func strconvFormat(value int64) string { return fmt.Sprintf("%d", value) }
