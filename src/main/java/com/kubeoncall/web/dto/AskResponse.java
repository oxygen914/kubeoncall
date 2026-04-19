package com.kubeoncall.web.dto;

public record AskResponse(
        String executionId,
        String status,
        String message
) {
}
