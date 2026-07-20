package com.kubeoncall.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MemoryExtractionTask(
        String id,
        MemoryType memoryType,
        MemoryScope scope,
        String subject,
        String content,
        String service,
        String resource,
        String fingerprint,
        Map<String, String> metadata,
        int attempts,
        Instant createdAt) {
    public MemoryExtractionTask {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static MemoryExtractionTask create(
            MemoryType memoryType,
            MemoryScope scope,
            String subject,
            String content,
            String service,
            String resource,
            String fingerprint,
            Map<String, String> metadata) {
        return new MemoryExtractionTask(
                UUID.randomUUID().toString(),
                memoryType,
                scope,
                subject,
                content,
                service,
                resource,
                fingerprint,
                metadata,
                0,
                Instant.now());
    }

    public MemoryExtractionTask retry(String error) {
        java.util.LinkedHashMap<String, String> nextMetadata = new java.util.LinkedHashMap<>(metadata);
        nextMetadata.put("last_error", error == null ? "unknown" : error);
        nextMetadata.put("last_attempt_at", Instant.now().toString());
        return new MemoryExtractionTask(
                id,
                memoryType,
                scope,
                subject,
                content,
                service,
                resource,
                fingerprint,
                nextMetadata,
                attempts + 1,
                createdAt);
    }
}
