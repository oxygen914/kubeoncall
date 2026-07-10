package com.kubeoncall.web.dto;

import java.util.Map;

public record MemorySearchRequest(
        String query,
        Map<String, String> filters,
        Integer topK,
        Boolean includeTrace
) {
    public MemorySearchRequest {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }
}
