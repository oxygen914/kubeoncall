package com.kubeoncall.task.worker;

/** Signals an input/policy failure that must fail the current durable task without another retry. */
public final class NonRetryableTaskException extends RuntimeException {

    private final String errorCode;

    public NonRetryableTaskException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode == null || errorCode.isBlank() ? "NON_RETRYABLE_FAILURE" : errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
