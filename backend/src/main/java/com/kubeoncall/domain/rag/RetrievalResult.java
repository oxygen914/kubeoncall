package com.kubeoncall.domain.rag;

import java.util.List;
import java.util.Map;

public record RetrievalResult(
        String rewrittenQuery,
        List<KnowledgeDocument> documents,
        String route,
        String summary,
        List<String> retrievalReasons,
        Map<String, Object> diagnostics) {}
