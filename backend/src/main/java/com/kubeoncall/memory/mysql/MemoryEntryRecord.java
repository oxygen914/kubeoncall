package com.kubeoncall.memory.mysql;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** API-safe durable memory metadata and evidence. */
public record MemoryEntryRecord(
        String publicId,
        String memoryType,
        String status,
        String sourceSessionId,
        String sourceExecutionPublicId,
        String sourceAlarmPublicId,
        Map<String, Object> evidence,
        BigDecimal qualityScore,
        String contentChecksum,
        String esIndex,
        String esDocumentId,
        Instant extractedAt,
        Instant expiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String deleteReason) {}
