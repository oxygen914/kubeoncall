package com.kubeoncall.workflow.execution;

import java.time.Instant;

/** Durable node-attempt fact belonging to a workflow execution. */
public record WorkflowNodeExecutionRecord(
        long id,
        String publicId,
        long executionId,
        String executionPublicId,
        String nodeName,
        String nodeType,
        int attempt,
        String status,
        String inputSummary,
        String outputSummary,
        String errorCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
