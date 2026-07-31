package com.kubeoncall.workflow.execution;

import java.time.Instant;

/** Durable, queryable summary of one workflow run. */
public record WorkflowExecutionRecord(
        long id,
        String publicId,
        String type,
        String triggerType,
        String triggerPublicId,
        String dedupeKey,
        String status,
        String riskLevel,
        String summary,
        String resultSummary,
        String errorCode,
        String errorSummary,
        String actorType,
        Long actorId,
        String sessionId,
        String requestId,
        String traceId,
        String graphStateKey,
        Instant startedAt,
        Instant finishedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
