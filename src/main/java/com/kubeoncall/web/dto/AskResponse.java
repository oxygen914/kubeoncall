package com.kubeoncall.web.dto;

public record AskResponse(
        String executionId,
        String status,
        String message,
        String sessionId
) {
    public AskResponse(String executionId, String status, String message) {
        this(executionId, status, message, null);
    }
}
