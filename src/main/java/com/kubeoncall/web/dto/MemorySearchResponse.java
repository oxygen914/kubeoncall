package com.kubeoncall.web.dto;

import com.kubeoncall.memory.MemoryEntry;

import java.util.List;
import java.util.Map;

public record MemorySearchResponse(
        List<MemoryEntry> entries,
        Map<String, Object> diagnostics
) {
    public MemorySearchResponse {
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
