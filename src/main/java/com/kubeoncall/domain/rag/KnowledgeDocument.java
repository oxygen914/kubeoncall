package com.kubeoncall.domain.rag;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record KnowledgeDocument(
        String id,
        String title,
        String content,
        String source,
        Map<String, String> metadata,
        Instant createdAt,
        String embeddingText,
        List<Double> embedding) {
    public KnowledgeDocument(
            String id, String title, String content, String source, Map<String, String> metadata, Instant createdAt) {
        this(id, title, content, source, metadata, createdAt, null, List.of());
    }
}
