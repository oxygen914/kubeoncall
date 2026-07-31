package com.kubeoncall.workflow.runtime;

/** Stable command failure categories shared by workflow/approval services and the v1 API layer. */
public class WorkflowCommandException extends RuntimeException {

    private final Code code;

    public WorkflowCommandException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID,
        NOT_FOUND,
        CONFLICT,
        RESOURCE_VERSION_CONFLICT,
        SERVICE_UNAVAILABLE
    }
}
