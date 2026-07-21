package com.kubeoncall.knowledge.mysql;

import java.time.Instant;

/** API-safe knowledge import fact with MinIO references instead of content blobs. */
public record KnowledgeImportRecord(
        String publicId,
        String taskPublicId,
        String importType,
        String duplicatePolicy,
        boolean dryRun,
        String status,
        String sourceBucket,
        String sourceObjectKey,
        String sourceChecksum,
        long sourceSizeBytes,
        String datasetVersion,
        Long totalCount,
        long processedCount,
        long succeededCount,
        long failedCount,
        long skippedCount,
        String errorReportBucket,
        String errorReportObjectKey,
        String errorCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
