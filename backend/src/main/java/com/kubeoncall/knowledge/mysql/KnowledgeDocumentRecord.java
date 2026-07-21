package com.kubeoncall.knowledge.mysql;

import java.time.Instant;
import java.util.Map;

/** API-safe knowledge-document fact. Internal database keys are deliberately omitted. */
public record KnowledgeDocumentRecord(
        String publicId,
        String externalDocumentId,
        String title,
        String sourceType,
        String sourceUri,
        String datasetVersion,
        String status,
        Map<String, Object> metadata,
        String currentVersionPublicId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String deleteReason) {}
