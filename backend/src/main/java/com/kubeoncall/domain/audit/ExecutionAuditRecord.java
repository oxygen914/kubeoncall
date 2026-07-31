package com.kubeoncall.domain.audit;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        long toolFailureCount,
        Map<String, Object> metadata) {
    public ExecutionAuditRecord(
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
            long toolFailureCount) {
        this(
                executionId,
                requestType,
                status,
                approvalRequired,
                autoHandled,
                durationMs,
                summary,
                failureReason,
                tools,
                occurredAt,
                retryCount,
                replanRequired,
                degraded,
                approvalLatencyMs,
                toolSuccessCount,
                toolFailureCount,
                Map.of());
    }

    public ExecutionAuditRecord {
        tools = tools == null ? List.of() : List.copyOf(tools);
        metadata = sanitizeMetadata(metadata);
    }

    private static Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, sanitizeValue(value));
            }
        });
        if (sanitized.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (key != null && nestedValue != null) {
                    nested.put(String.valueOf(key), sanitizeValue(nestedValue));
                }
            });
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ExecutionAuditRecord::sanitizeValue)
                    .toList();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value;
    }
}
