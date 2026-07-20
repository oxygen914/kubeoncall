package com.kubeoncall.memory;

import java.util.List;

/** A structured candidate emitted by the LLM extractor before quality filtering. */
public record MemoryExtractionCandidate(
        MemoryType memoryType,
        MemoryScope scope,
        String subject,
        String content,
        String service,
        String resource,
        String fingerprint,
        List<String> evidence,
        Double confidence) {
    public MemoryExtractionCandidate {
        evidence = evidence == null
                ? List.of()
                : evidence.stream()
                        .filter(item -> item != null && !item.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }
}
