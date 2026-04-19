package com.kubeoncall.domain.rag;

import java.util.Map;

public record RetrievalRequest(
        String question,
        Map<String, String> filters,
        int topK
) {
}
