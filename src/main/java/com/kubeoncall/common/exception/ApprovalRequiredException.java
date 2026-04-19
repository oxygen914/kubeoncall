package com.kubeoncall.common.exception;

public class ApprovalRequiredException extends KubeOnCallException {

    private final String executionId;

    public ApprovalRequiredException(String executionId, String message) {
        super(message);
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
