package com.kubeoncall.sandbox;

/**
 * Sanitized failure from the internal Sandbox Controller transport.
 *
 * <p>The controller may include implementation-specific error text in an HTTP response. That body
 * is intentionally never copied into this exception, task error summaries, or application logs.
 */
public final class SandboxControllerClientException extends RuntimeException {

    private final boolean retryable;
    private final int statusCode;

    SandboxControllerClientException(String code, boolean retryable, int statusCode, Throwable cause) {
        super(code, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    SandboxControllerClientException(String code, boolean retryable, int statusCode) {
        this(code, retryable, statusCode, null);
    }

    public boolean retryable() {
        return retryable;
    }

    public int statusCode() {
        return statusCode;
    }
}
