package com.kubeoncall.rag;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;

@Service
public class KnowledgeIngestService {

    private final KnowledgeIngestionFacade ingestionFacade;
    private final KnowledgeRetrievalFacade retrievalFacade;

    public KnowledgeIngestService(KnowledgeIngestionFacade ingestionFacade, KnowledgeRetrievalFacade retrievalFacade) {
        this.ingestionFacade = ingestionFacade;
        this.retrievalFacade = retrievalFacade;
    }

    public KnowledgeDocument ingest(String title, String content, String source, Map<String, String> metadata) {
        return ingestionFacade.ingest(title, content, source, metadata);
    }

    public KnowledgeIngestionFacade.IngestionResult ingestWithResult(
            String title, String content, String source, Map<String, String> metadata) {
        return ingestionFacade.ingestWithResult(title, content, source, metadata);
    }

    public KnowledgeIngestionFacade.LifecycleResult softDelete(String documentId, String reason) {
        return ingestionFacade.softDelete(documentId, reason);
    }

    public KnowledgeIngestionFacade.LifecycleResult restore(String documentId) {
        return ingestionFacade.restore(documentId);
    }

    public RetrievalResult retrieve(String question, Map<String, String> filters) {
        // Preserve the legacy service contract: callers of this overload receive diagnostics.
        // The explicit API overload remains the opt-in trace boundary.
        return retrievalFacade.retrieve(question, filters, null, RetrieveMethod.HYBRID, true);
    }

    public RetrievalResult retrieve(
            String question,
            Map<String, String> filters,
            Integer topK,
            RetrieveMethod retrieveMethod,
            boolean includeTrace) {
        return retrievalFacade.retrieve(question, filters, topK, retrieveMethod, includeTrace);
    }
}
