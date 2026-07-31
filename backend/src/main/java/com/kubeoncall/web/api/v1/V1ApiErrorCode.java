package com.kubeoncall.web.api.v1;

/**
 * Stable machine-readable error codes for the {@code /api/v1} surface. Codes are part of the public
 * contract: the frontend maps them to UX and must never parse {@code message}.
 */
public enum V1ApiErrorCode {
    INVALID_REQUEST(false),
    MALFORMED_REQUEST(false),
    VALIDATION_FAILED(false),
    UNAUTHENTICATED(false),
    AUTH_INVALID_CREDENTIALS(false),
    AUTH_ACCOUNT_LOCKED(true),
    AUTH_SESSION_EXPIRED(false),
    FORBIDDEN(false),
    NOT_FOUND(false),
    CONFLICT(false),
    RESOURCE_VERSION_CONFLICT(false),
    IDEMPOTENCY_KEY_REUSED(false),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(true),
    RATE_LIMITED(true),
    SERVICE_UNAVAILABLE(true),
    INTERNAL_ERROR(false);

    private final boolean retryable;

    V1ApiErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
