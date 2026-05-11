package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;

import java.util.List;
import java.util.Map;

public interface CrossEncoderReranker {

    boolean available();

    Map<String, Double> score(String query, List<KnowledgeDocument> documents);
}
