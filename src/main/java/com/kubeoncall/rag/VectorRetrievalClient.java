package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;

import java.util.List;

public interface VectorRetrievalClient {

    boolean available();

    List<KnowledgeDocument> search(RetrievalRequest request, int candidateSize);
}
