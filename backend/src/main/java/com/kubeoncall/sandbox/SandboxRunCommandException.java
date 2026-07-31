package com.kubeoncall.sandbox;

/** Stable command-layer failure for the V1 sandbox lifecycle surface. */
public class SandboxRunCommandException extends RuntimeException {

    public enum Code {
        INVALID,
        NOT_FOUND,
        VERSION_CONFLICT,
        SERVICE_UNAVAILABLE,
        IDEMPOTENCY_IN_PROGRESS,
        IDEMPOTENCY_REUSED
    }

    private final Code code;

    public SandboxRunCommandException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
