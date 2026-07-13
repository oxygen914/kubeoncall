package com.kubeoncall.web.dto;

import java.util.Map;

public record AskResponse(
        String executionId, String status, String message, String sessionId, Map<String, Object> details) {
    public AskResponse(String executionId, String status, String message) {
        this(executionId, status, message, null, Map.of());
    }

    public AskResponse(String executionId, String status, String message, String sessionId) {
        this(executionId, status, message, sessionId, Map.of());
    }
}
