package com.kubeoncall.rag;

import java.util.List;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalHit;
import com.kubeoncall.domain.rag.RetrievalRequest;

public interface VectorRetriever {

    boolean available();

    List<KnowledgeDocument> retrieve(RetrievalRequest request, int candidateSize);

    default List<RetrievalHit> retrieveHits(RetrievalRequest request, int candidateSize) {
        List<KnowledgeDocument> documents = retrieve(request, candidateSize);
        if (documents == null) {
            return List.of();
        }
        java.util.concurrent.atomic.AtomicInteger rank = new java.util.concurrent.atomic.AtomicInteger(1);
        return documents.stream()
                .map(document -> new RetrievalHit(document, null, rank.getAndIncrement(), "VECTOR"))
                .toList();
    }

    default String source() {
        return getClass().getSimpleName();
    }
}
