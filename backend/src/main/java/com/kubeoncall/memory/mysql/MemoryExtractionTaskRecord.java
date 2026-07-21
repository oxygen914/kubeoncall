package com.kubeoncall.memory.mysql;

import java.time.Instant;
import java.util.Map;

/** API-safe memory extraction task projection. */
public record MemoryExtractionTaskRecord(
        String publicId,
        String taskPublicId,
        String sourceType,
        String sourcePublicId,
        String dedupeKey,
        String status,
        String extractorModel,
        String extractorVersion,
        int evidenceCount,
        int memoryCount,
        Map<String, Object> qualitySummary,
        String errorCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
