package com.kubeoncall.domain.audit;

import java.time.Instant;
import java.util.List;

public record ExecutionAuditRecord(
        String executionId,
        ExecutionRequestType requestType,
        String status,
        boolean approvalRequired,
        boolean autoHandled,
        long durationMs,
        String summary,
        String failureReason,
        List<String> tools,
        Instant occurredAt,
        int retryCount,
        boolean replanRequired,
        boolean degraded,
        long approvalLatencyMs,
        long toolSuccessCount,
        long toolFailureCount
) {
}
