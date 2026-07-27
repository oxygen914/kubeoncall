package httpapi

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// FuzzAuthenticatorRejectsMalformedInput keeps malformed internal requests on the reject path and,
// most importantly, proves that arbitrary header/body bytes cannot panic the HMAC/replay boundary.
// Valid signed requests remain covered by the deterministic lifecycle tests.
func FuzzAuthenticatorRejectsMalformedInput(f *testing.F) {
	f.Add("", "", "", []byte(""))
	f.Add("unknown", "not-a-time", "short", []byte("{\"runId\":\"sbx_a1b2\"}"))
	f.Add("backend", "999999999999999999999", "nonce-0123456789abcdef", []byte{0xff, 0x00})

	f.Fuzz(func(t *testing.T, keyID, timestamp, nonce string, body []byte) {
		config := testConfig()
		auth := newAuthenticator(config)
		auth.clock = func() time.Time { return time.Unix(1_700_000_000, 0) }
		request := httptest.NewRequest(http.MethodPost, "/internal/v1/runs", bytes.NewReader(body))
		request.Header.Set(headerKeyID, keyID)
		request.Header.Set(headerTimestamp, timestamp)
		request.Header.Set(headerNonce, nonce)
		request.Header.Set(headerSignature, "not-a-valid-hex-signature")
		_ = auth.verify(request, body)
	})
}
