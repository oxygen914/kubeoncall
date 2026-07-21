package com.kubeoncall.knowledge.mysql;

import java.time.Instant;
import java.util.Map;

/** API-safe immutable knowledge content version. */
public record KnowledgeDocumentVersionRecord(
        String publicId,
        String documentPublicId,
        String importPublicId,
        int versionNumber,
        String checksum,
        String contentType,
        long sizeBytes,
        String objectBucket,
        String objectKey,
        String esIndex,
        String esDocumentId,
        String indexStatus,
        String embeddingModel,
        String embeddingVersion,
        Integer embeddingDimensions,
        String augmentationModel,
        String augmentationVersion,
        Map<String, Object> metadata,
        Instant indexedAt,
        Instant createdAt) {}
