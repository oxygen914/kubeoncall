package com.kubeoncall.domain.rag;

public record RetrievalHit(
        KnowledgeDocument document,
        Double rawScore,
        int rank,
        String channel
) {
}
