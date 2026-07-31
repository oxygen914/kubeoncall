package com.kubeoncall.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MemoryEntry(
        String id,
        MemoryType type,
        MemoryScope scope,
        String subject,
        String content,
        String service,
        String resource,
        String fingerprint,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> metadata,
        Integer tokenCount,
        Boolean enabled) {

    public MemoryEntry(
            String id,
            MemoryType type,
            MemoryScope scope,
            String subject,
            String content,
            String service,
            String resource,
            String fingerprint,
            Instant createdAt,
            Instant updatedAt,
            Map<String, String> metadata) {
        this(
                id,
                type,
                scope,
                subject,
                content,
                service,
                resource,
                fingerprint,
                createdAt,
                updatedAt,
                metadata,
                null,
                null);
    }

    public MemoryEntry {
        if (id == null || id.isBlank()) {
            id = "memory-" + UUID.randomUUID();
        }
        if (type == null) {
            type = MemoryType.INCIDENT_SUMMARY;
        }
        if (scope == null) {
            scope = MemoryScope.GLOBAL;
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        tokenCount = tokenCount == null ? integer(metadata.get("token_count"), 0) : Math.max(0, tokenCount);
        enabled = enabled == null ? !"false".equalsIgnoreCase(metadata.get("memory_enabled")) : enabled;
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
