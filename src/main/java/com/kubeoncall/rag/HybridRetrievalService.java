package com.kubeoncall.rag;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridRetrievalService {

    private final KnowledgeRepository knowledgeRepository;

    public HybridRetrievalService(KnowledgeRepository knowledgeRepository) {
        this.knowledgeRepository = knowledgeRepository;
    }

    public List<KnowledgeDocument> retrieve(RetrievalRequest request) {
        return knowledgeRepository.search(request);
    }

    public RetrievalTrace retrieveWithTrace(RetrievalRequest request) {
        Instant startedAt = Instant.now();
        List<KnowledgeDocument> documents = retrieve(request);
        List<String> reasons = new ArrayList<>();
        reasons.add("Applied lexical retrieval over title/content");
        if (request.filters() != null && !request.filters().isEmpty()) {
            reasons.add("Applied metadata filters: " + request.filters().keySet());
        }
        reasons.add("Limited topK to " + request.topK());
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", documents.size());
        diagnostics.put("latencyMs", Duration.between(startedAt, Instant.now()).toMillis());
        return new RetrievalTrace(documents, reasons, diagnostics);
    }

    public record RetrievalTrace(
            List<KnowledgeDocument> documents,
            List<String> reasons,
            Map<String, Object> diagnostics
    ) {
    }
}
