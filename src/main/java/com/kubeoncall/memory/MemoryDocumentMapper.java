package com.kubeoncall.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.rag.KnowledgeDocument;

@Component
public class MemoryDocumentMapper {

    public KnowledgeDocument toKnowledgeDocument(MemoryEntry entry) {
        Map<String, String> metadata = new LinkedHashMap<>(entry.metadata());
        metadata.put("source_type", "memory");
        metadata.put("memory_type", entry.type().name());
        metadata.put("memory_scope", entry.scope().name());
        putIfPresent(metadata, "subject", entry.subject());
        putIfPresent(metadata, "service", entry.service());
        putIfPresent(metadata, "resource", entry.resource());
        putIfPresent(metadata, "fingerprint", entry.fingerprint());
        metadata.put("created_at", entry.createdAt().toString());
        metadata.put("updated_at", entry.updatedAt().toString());
        metadata.put("memory_enabled", "true");
        metadata.put("chunk_enable", "true");

        String title = entry.subject() == null || entry.subject().isBlank()
                ? entry.type().name() + " memory"
                : entry.subject();
        return new KnowledgeDocument(entry.id(), title, entry.content(), "memory", metadata, entry.createdAt());
    }

    public MemoryEntry toMemoryEntry(KnowledgeDocument document) {
        Map<String, String> metadata = document.metadata() == null ? Map.of() : document.metadata();
        return new MemoryEntry(
                document.id(),
                enumValue(MemoryType.class, metadata.get("memory_type"), MemoryType.INCIDENT_SUMMARY),
                enumValue(MemoryScope.class, metadata.get("memory_scope"), MemoryScope.GLOBAL),
                metadata.getOrDefault("subject", document.title()),
                document.content(),
                metadata.get("service"),
                metadata.get("resource"),
                metadata.get("fingerprint"),
                instant(metadata.get("created_at"), document.createdAt()),
                instant(metadata.get("updated_at"), document.createdAt()),
                metadata);
    }

    public boolean isMemoryDocument(KnowledgeDocument document) {
        if (document == null) {
            return false;
        }
        if ("memory".equalsIgnoreCase(document.source())) {
            return true;
        }
        return document.metadata() != null
                && "memory".equalsIgnoreCase(document.metadata().get("source_type"));
    }

    public boolean isEnabled(KnowledgeDocument document) {
        if (document == null || document.metadata() == null) {
            return true;
        }
        return !"false".equalsIgnoreCase(document.metadata().get("memory_enabled"));
    }

    public boolean isCleanupEligible(KnowledgeDocument document, Instant staleThreshold) {
        MemoryEntry entry = toMemoryEntry(document);
        Instant updatedAt = entry.updatedAt() == null ? entry.createdAt() : entry.updatedAt();
        if (!isEnabled(document) || updatedAt == null || !updatedAt.isBefore(staleThreshold)) {
            return false;
        }
        return entry.type() == MemoryType.DEVICE_HISTORY || entry.type() == MemoryType.INCIDENT_SUMMARY;
    }

    public KnowledgeDocument softDeleted(KnowledgeDocument document, Instant deletedAt) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (document.metadata() != null) {
            metadata.putAll(document.metadata());
        }
        metadata.put("memory_enabled", "false");
        metadata.put("chunk_enable", "false");
        metadata.put("deleted_at", deletedAt.toString());
        metadata.put("delete_reason", "stale_cleanup");
        metadata.put("updated_at", deletedAt.toString());
        return new KnowledgeDocument(
                document.id(), document.title(), document.content(), document.source(), metadata, document.createdAt());
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private Instant instant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? Instant.now() : fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return fallback == null ? Instant.now() : fallback;
        }
    }

    private void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
