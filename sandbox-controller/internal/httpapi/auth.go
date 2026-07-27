package httpapi

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	headerKeyID     = "X-Sandbox-Key-Id"
	headerTimestamp = "X-Sandbox-Timestamp"
	headerNonce     = "X-Sandbox-Nonce"
	headerSignature = "X-Sandbox-Signature"
)

type authenticator struct {
	keyID     string
	secret    []byte
	clockSkew time.Duration
	nonces    *nonceStore
	clock     func() time.Time
}

func newAuthenticator(config Config) *authenticator {
	return &authenticator{
		keyID: config.BackendKeyID, secret: []byte(config.BackendHMACSecret), clockSkew: config.ClockSkew,
		nonces: newNonceStore(), clock: time.Now,
	}
}

func (a *authenticator) verify(request *http.Request, body []byte) error {
	if len(a.secret) == 0 || request.Header.Get(headerKeyID) != a.keyID {
		return errors.New("unknown backend key")
	}
	timestamp, err := strconv.ParseInt(request.Header.Get(headerTimestamp), 10, 64)
	if err != nil || timestamp <= 0 || absDuration(a.clock().Sub(time.Unix(timestamp, 0))) > a.clockSkew {
		return errors.New("expired or invalid request timestamp")
	}
	nonce := request.Header.Get(headerNonce)
	if len(nonce) < 16 || len(nonce) > 128 {
		return errors.New("invalid request nonce")
	}
	expected := sign(a.secret, request.Method, request.URL.EscapedPath(), request.Header.Get(headerTimestamp), nonce, body)
	provided, err := hex.DecodeString(request.Header.Get(headerSignature))
	if err != nil || !hmac.Equal(expected, provided) {
		return errors.New("invalid request signature")
	}
	if !a.nonces.claim(nonce, time.Unix(timestamp, 0).Add(a.clockSkew), a.clock()) {
		return errors.New("replayed request nonce")
	}
	return nil
}

func sign(secret []byte, method, path, timestamp, nonce string, body []byte) []byte {
	bodyDigest := sha256.Sum256(body)
	canonical := strings.Join([]string{method, path, timestamp, nonce, hex.EncodeToString(bodyDigest[:])}, "\n")
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(canonical))
	return mac.Sum(nil)
}

type nonceStore struct {
	mu     sync.Mutex
	values map[string]time.Time
}

func newNonceStore() *nonceStore { return &nonceStore{values: make(map[string]time.Time)} }

func (store *nonceStore) claim(nonce string, expiresAt, now time.Time) bool {
	store.mu.Lock()
	defer store.mu.Unlock()
	for value, expires := range store.values {
		if !expires.After(now) {
			delete(store.values, value)
		}
	}
	if _, exists := store.values[nonce]; exists {
		return false
	}
	store.values[nonce] = expiresAt
	return true
}

func absDuration(value time.Duration) time.Duration {
	if value < 0 {
		return -value
	}
	return value
}
