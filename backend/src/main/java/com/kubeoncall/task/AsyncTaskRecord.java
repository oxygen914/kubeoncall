package com.kubeoncall.task;

import java.time.Instant;
import java.util.Map;

/** Durable async-task state, including lease ownership and fencing metadata. */
public record AsyncTaskRecord(
        long id,
        String publicId,
        String taskType,
        String resourceType,
        String resourcePublicId,
        String dedupeKey,
        String status,
        String stage,
        int progress,
        Map<String, Object> request,
        Map<String, Object> result,
        String errorCode,
        String errorSummary,
        String ownerToken,
        Instant leaseUntil,
        long fencingToken,
        int attempt,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant startedAt,
        Instant finishedAt,
        String requestId,
        String traceId,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
