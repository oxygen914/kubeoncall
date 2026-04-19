package com.kubeoncall.rag.repository;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;

import java.util.List;

public interface KnowledgeRepository {

    void save(KnowledgeDocument document);

    List<KnowledgeDocument> search(RetrievalRequest request);
}
