package com.kubeoncall.domain.rag;

import java.time.Instant;
import java.util.Map;

public record KnowledgeDocument(
        String id,
        String title,
        String content,
        String source,
        Map<String, String> metadata,
        Instant createdAt
) {
}
