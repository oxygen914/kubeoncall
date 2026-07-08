package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;

import java.util.List;

public interface VectorRetriever {

    boolean available();

    List<KnowledgeDocument> retrieve(RetrievalRequest request, int candidateSize);

    default String source() {
        return getClass().getSimpleName();
    }
}
