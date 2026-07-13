package com.kubeoncall.web.dto;

import java.util.List;
import java.util.Map;

import com.kubeoncall.memory.MemoryEntry;

public record MemorySearchResponse(List<MemoryEntry> entries, Map<String, Object> diagnostics) {
    public MemorySearchResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
