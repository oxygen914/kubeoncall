package com.kubeoncall.rag.repository;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface KnowledgeRepository {

    void save(KnowledgeDocument document);

    List<KnowledgeDocument> searchLexical(RetrievalRequest request, int candidateSize);

    List<KnowledgeDocument> searchVector(RetrievalRequest request, int candidateSize);

    default List<KnowledgeDocument> searchVector(RetrievalRequest request,
                                                 int candidateSize,
                                                 List<Double> queryVector) {
        return searchVector(request, candidateSize);
    }

    Map<String, KnowledgeDocument> loadParents(List<String> parentDocumentIds);

    default Optional<KnowledgeDocument> findById(String documentId) {
        return Optional.empty();
    }

    default void deleteById(String documentId) {
    }

    default List<KnowledgeDocument> search(RetrievalRequest request) {
        return searchLexical(request, Math.max(1, request.topK()));
    }
}
