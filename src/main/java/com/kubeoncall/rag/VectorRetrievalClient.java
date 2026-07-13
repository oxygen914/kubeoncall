package com.kubeoncall.rag;

import java.util.List;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;

public interface VectorRetrievalClient extends VectorRetriever {

    List<KnowledgeDocument> search(RetrievalRequest request, int candidateSize);

    @Override
    default List<KnowledgeDocument> retrieve(RetrievalRequest request, int candidateSize) {
        return search(request, candidateSize);
    }

    @Override
    default String source() {
        return "external_vector_service";
    }
}
