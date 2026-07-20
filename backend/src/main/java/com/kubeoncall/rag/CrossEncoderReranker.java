package com.kubeoncall.rag;

import java.util.List;
import java.util.Map;

import com.kubeoncall.domain.rag.KnowledgeDocument;

public interface CrossEncoderReranker {

    boolean available();

    Map<String, Double> score(String query, List<KnowledgeDocument> documents);
}
