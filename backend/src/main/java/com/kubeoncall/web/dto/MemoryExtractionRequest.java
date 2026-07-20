package com.kubeoncall.web.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryType;

public record MemoryExtractionRequest(
        MemoryType memoryType,
        MemoryScope scope,
        String subject,
        @NotBlank String content,
        String service,
        String resource,
        String fingerprint,
        Map<String, String> metadata) {

    public MemoryExtractionRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
