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
        Map<String, String> metadata
) {
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
    }
}
