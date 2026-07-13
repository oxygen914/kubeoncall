package com.kubeoncall.rag.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalHit;
import com.kubeoncall.domain.rag.RetrievalRequest;

public interface KnowledgeRepository {

    void save(KnowledgeDocument document);

    List<KnowledgeDocument> searchLexical(RetrievalRequest request, int candidateSize);

    List<KnowledgeDocument> searchVector(RetrievalRequest request, int candidateSize);

    default List<RetrievalHit> searchLexicalHits(RetrievalRequest request, int candidateSize) {
        return toHits(searchLexical(request, candidateSize), "LEXICAL");
    }

    default List<RetrievalHit> searchVectorHits(RetrievalRequest request, int candidateSize, List<Double> queryVector) {
        return toHits(searchVector(request, candidateSize, queryVector), "VECTOR");
    }

    default List<KnowledgeDocument> searchVector(
            RetrievalRequest request, int candidateSize, List<Double> queryVector) {
        return searchVector(request, candidateSize);
    }

    Map<String, KnowledgeDocument> loadParents(List<String> parentDocumentIds);

    default Optional<KnowledgeDocument> findById(String documentId) {
        return Optional.empty();
    }

    default void deleteById(String documentId) {}

    default List<KnowledgeDocument> search(RetrievalRequest request) {
        return searchLexical(request, Math.max(1, request.topK()));
    }

    private static List<RetrievalHit> toHits(List<KnowledgeDocument> documents, String channel) {
        if (documents == null) {
            return List.of();
        }
        java.util.concurrent.atomic.AtomicInteger rank = new java.util.concurrent.atomic.AtomicInteger(1);
        return documents.stream()
                .map(document -> new RetrievalHit(document, null, rank.getAndIncrement(), channel))
                .toList();
    }
}
