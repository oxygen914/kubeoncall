package com.kubeoncall.domain.rag;

import java.util.Map;

public record RetrievalRequest(
        String question, Map<String, String> filters, int topK, RetrieveMethod retrieveMethod, boolean includeTrace) {
    public RetrievalRequest(String question, Map<String, String> filters, int topK) {
        this(question, filters, topK, RetrieveMethod.HYBRID, false);
    }

    public RetrievalRequest {
        if (retrieveMethod == null) {
            retrieveMethod = RetrieveMethod.HYBRID;
        }
    }
}
