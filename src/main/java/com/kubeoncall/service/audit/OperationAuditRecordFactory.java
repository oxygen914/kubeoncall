package com.kubeoncall.service.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import com.kubeoncall.domain.audit.ExecutionRequestType;

@Component
public class OperationAuditRecordFactory {

    public ExecutionAuditRecord memory(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        String normalizedOperation = normalizedOperation(operation);
        String normalizedStatus = normalizedStatus(status);
        boolean failed = "FAILED".equals(normalizedStatus);
        return new ExecutionAuditRecord(
                "memory-" + normalizedOperation + "-" + UUID.randomUUID(),
                ExecutionRequestType.MEMORY,
                normalizedStatus,
                false,
                true,
                durationMs(startedAt),
                summary,
                failed ? summary : null,
                List.of(),
                Instant.now(),
                0,
                false,
                failed,
                0,
                0,
                0,
                metadata("memoryOperation", normalizedOperation, metadata));
    }

    public ExecutionAuditRecord knowledge(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        return operation(
                ExecutionRequestType.KNOWLEDGE,
                "knowledge",
                "knowledgeOperation",
                operation,
                status,
                summary,
                startedAt,
                metadata);
    }

    public ExecutionAuditRecord skill(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        return operation(
                ExecutionRequestType.SKILL, "skill", "skillOperation", operation, status, summary, startedAt, metadata);
    }

    public ExecutionAuditRecord changeEvent(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        return operation(
                ExecutionRequestType.CHANGE_EVENT,
                "change-event",
                "changeEventOperation",
                operation,
                status,
                summary,
                startedAt,
                metadata);
    }

    private ExecutionAuditRecord operation(
            ExecutionRequestType requestType,
            String idPrefix,
            String operationMetadataKey,
            String operation,
            String status,
            String summary,
            Instant startedAt,
            Map<String, Object> metadata) {
        String normalizedOperation = normalizedOperation(operation);
        String normalizedStatus = normalizedStatus(status);
        boolean failed = "FAILED".equals(normalizedStatus);
        return new ExecutionAuditRecord(
                idPrefix + "-" + normalizedOperation + "-" + UUID.randomUUID(),
                requestType,
                normalizedStatus,
                false,
                true,
                durationMs(startedAt),
                summary,
                failed ? summary : null,
                List.of(idPrefix + "." + normalizedOperation),
                Instant.now(),
                0,
                false,
                failed,
                0,
                failed ? 0 : 1,
                failed ? 1 : 0,
                metadata(operationMetadataKey, normalizedOperation, metadata));
    }

    private String normalizedOperation(String operation) {
        return operation == null || operation.isBlank() ? "unknown" : operation.trim();
    }

    private String normalizedStatus(String status) {
        return status == null || status.isBlank() ? "UNKNOWN" : status.trim().toUpperCase(Locale.ROOT);
    }

    private long durationMs(Instant startedAt) {
        Instant start = startedAt == null ? Instant.now() : startedAt;
        return Math.max(0, Duration.between(start, Instant.now()).toMillis());
    }

    private Map<String, Object> metadata(String operationKey, String operation, Map<String, Object> metadata) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.put(operationKey, operation);
        if (metadata != null) {
            merged.putAll(metadata);
        }
        return merged;
    }
}
