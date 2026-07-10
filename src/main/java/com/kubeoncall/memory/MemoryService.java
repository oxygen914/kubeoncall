package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MemoryService {

    private final KnowledgeRepository knowledgeRepository;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;

    @Autowired
    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties,
                         KubeOnCallMetricsService metricsService) {
        this.knowledgeRepository = knowledgeRepository;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties) {
        this(knowledgeRepository, properties, null);
    }

    public MemoryEntry remember(MemoryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("memory entry must not be null");
        }
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            recordMetric("remember", "disabled", 0);
            return entry;
        }
        knowledgeRepository.save(toKnowledgeDocument(entry));
        recordMetric("remember", "success", 1);
        return entry;
    }

    public List<MemoryEntry> search(String query, Map<String, String> filters, int topK) {
        if (!properties.getMemory().isEnabled()) {
            recordMetric("search", "disabled", 0);
            return List.of();
        }
        Map<String, String> effectiveFilters = new LinkedHashMap<>();
        if (filters != null) {
            effectiveFilters.putAll(filters);
        }
        effectiveFilters.put("source_type", "memory");
        RetrievalRequest request = new RetrievalRequest(
                query,
                effectiveFilters,
                Math.max(1, topK)
        );
        int candidateSize = Math.max(1, topK) * 3;
        List<MemoryEntry> entries = knowledgeRepository.searchLexical(request, candidateSize).stream()
                .filter(this::isEnabledMemoryDocument)
                .map(this::toMemoryEntry)
                .limit(Math.max(1, topK))
                .toList();
        recordMetric("search", "success", entries.size());
        return entries;
    }

    public MemoryCleanupResult cleanupStale(Instant now, int scanLimit) {
        return cleanupStale(now, scanLimit, false);
    }

    public MemoryCleanupResult cleanupStale(Instant now, int scanLimit, boolean dryRun) {
        Instant reference = now == null ? Instant.now() : now;
        int limit = Math.max(1, scanLimit);
        int staleAfterDays = Math.max(1, properties.getMemory().getStaleAfterDays());
        Instant threshold = reference.minus(Duration.ofDays(staleAfterDays));
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            recordMetric("cleanup", "disabled", 0);
            return new MemoryCleanupResult(0, 0, 0, "disabled", limit, threshold, staleAfterDays, dryRun);
        }
        RetrievalRequest request = new RetrievalRequest("", Map.of("source_type", "memory"), limit);
        List<KnowledgeDocument> candidates = knowledgeRepository.searchLexical(request, limit);

        int eligible = 0;
        int deleted = 0;
        for (KnowledgeDocument document : candidates) {
            MemoryEntry entry = toMemoryEntry(document);
            Instant updatedAt = entry.updatedAt() == null ? entry.createdAt() : entry.updatedAt();
            if (isCleanupEligible(document, entry, updatedAt, threshold)) {
                eligible++;
                if (dryRun) {
                    continue;
                }
                knowledgeRepository.save(softDeleted(document, reference));
                deleted++;
            }
        }
        String status = dryRun ? "dry_run" : "success";
        recordMetric("cleanup", status, dryRun ? eligible : deleted);
        return new MemoryCleanupResult(candidates.size(), eligible, deleted, status, limit, threshold, staleAfterDays, dryRun);
    }

    private KnowledgeDocument toKnowledgeDocument(MemoryEntry entry) {
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

    private MemoryEntry toMemoryEntry(KnowledgeDocument document) {
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
                metadata
        );
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

    private boolean isEnabledMemoryDocument(KnowledgeDocument document) {
        if (document == null || document.metadata() == null) {
            return true;
        }
        return !"false".equalsIgnoreCase(document.metadata().get("memory_enabled"));
    }

    private boolean isCleanupEligible(KnowledgeDocument document,
                                      MemoryEntry entry,
                                      Instant updatedAt,
                                      Instant threshold) {
        if (!isEnabledMemoryDocument(document) || updatedAt == null || !updatedAt.isBefore(threshold)) {
            return false;
        }
        return entry.type() == MemoryType.DEVICE_HISTORY || entry.type() == MemoryType.INCIDENT_SUMMARY;
    }

    private KnowledgeDocument softDeleted(KnowledgeDocument document, Instant deletedAt) {
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
                document.id(),
                document.title(),
                document.content(),
                document.source(),
                metadata,
                document.createdAt());
    }

    private void recordMetric(String operation, String outcome, long count) {
        if (metricsService != null) {
            metricsService.recordMemory(operation, outcome, count);
        }
    }

    public record MemoryCleanupResult(
            int scanned,
            int eligible,
            int deleted,
            String status,
            int scanLimit,
            Instant staleThreshold,
            int staleAfterDays,
            boolean dryRun
    ) {
    }
}
