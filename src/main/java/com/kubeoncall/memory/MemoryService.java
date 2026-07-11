package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.KnowledgeRetrievalFacade;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.service.ExecutionAuditService;
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
    private final KnowledgeRetrievalFacade retrievalFacade;
    private final ExecutionAuditService auditService;
    private final MemoryTemporalNormalizer temporalNormalizer;

    @Autowired
    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties,
                         KubeOnCallMetricsService metricsService,
                         KnowledgeRetrievalFacade retrievalFacade,
                         ExecutionAuditService auditService,
                         MemoryTemporalNormalizer temporalNormalizer) {
        this.knowledgeRepository = knowledgeRepository;
        this.properties = properties;
        this.metricsService = metricsService;
        this.retrievalFacade = retrievalFacade;
        this.auditService = auditService;
        this.temporalNormalizer = temporalNormalizer;
    }

    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties,
                         KubeOnCallMetricsService metricsService,
                         KnowledgeRetrievalFacade retrievalFacade,
                         ExecutionAuditService auditService) {
        this(knowledgeRepository, properties, metricsService, retrievalFacade, auditService, null);
    }

    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties,
                         KubeOnCallMetricsService metricsService,
                         KnowledgeRetrievalFacade retrievalFacade) {
        this(knowledgeRepository, properties, metricsService, retrievalFacade, null);
    }

    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties,
                         KubeOnCallMetricsService metricsService) {
        this(knowledgeRepository, properties, metricsService, null, null);
    }

    public MemoryService(KnowledgeRepository knowledgeRepository,
                         KubeOnCallProperties properties) {
        this(knowledgeRepository, properties, null);
    }

    public MemoryEntry remember(MemoryEntry entry) {
        Instant startedAt = Instant.now();
        if (entry == null) {
            throw new IllegalArgumentException("memory entry must not be null");
        }
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            recordMetric("remember", "disabled", 0);
            audit("remember", "disabled", "memory remember disabled", startedAt,
                    Map.of("memoryId", entry.id() == null ? "" : entry.id()));
            return entry;
        }
        try {
            MemoryEntry normalized = normalizeTemporal(entry);
            knowledgeRepository.save(toKnowledgeDocument(normalized));
            recordMetric("remember", "success", 1);
            audit("remember", "success", "memory remembered", startedAt, Map.of(
                    "memoryId", normalized.id() == null ? "" : normalized.id(),
                    "memoryType", normalized.type() == null ? "" : normalized.type().name(),
                    "memoryScope", normalized.scope() == null ? "" : normalized.scope().name()));
            return normalized;
        } catch (RuntimeException ex) {
            recordMetric("remember", "failed", 0);
            audit("remember", "failed", "memory remember failed", startedAt,
                    Map.of("memoryId", entry.id() == null ? "" : entry.id(),
                            "errorType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private MemoryEntry normalizeTemporal(MemoryEntry entry) {
        if (temporalNormalizer == null) {
            return entry;
        }
        try {
            MemoryEntry normalized = temporalNormalizer.normalize(entry, Instant.now());
            if (!normalized.equals(entry)) {
                recordMetric("normalize", "success", 1);
            }
            return normalized;
        } catch (RuntimeException ex) {
            recordMetric("normalize", "failed", 1);
            return entry;
        }
    }

    public List<MemoryEntry> search(String query, Map<String, String> filters, int topK) {
        return searchWithTrace(query, filters, topK).entries();
    }

    public MemorySearchResult searchWithTrace(String query, Map<String, String> filters, int topK) {
        Instant startedAt = Instant.now();
        if (!properties.getMemory().isEnabled()) {
            recordMetric("search", "disabled", 0);
            MemorySearchResult result = new MemorySearchResult(List.of(), Map.of(
                    "memorySearch", true,
                    "status", "disabled"));
            audit("search", "disabled", "memory search disabled", startedAt,
                    Map.of("topK", Math.max(1, topK)));
            return result;
        }
        Map<String, String> effectiveFilters = new LinkedHashMap<>();
        if (filters != null) {
            effectiveFilters.putAll(filters);
        }
        effectiveFilters.put("source_type", "memory");
        int resultLimit = Math.max(1, topK);
        int candidateSize = resultLimit * 3;
        List<KnowledgeDocument> documents;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        try {
            if (retrievalFacade == null) {
                RetrievalRequest request = new RetrievalRequest(query, effectiveFilters, resultLimit);
                documents = knowledgeRepository.searchLexical(request, candidateSize);
                diagnostics.put("rankingSource", "lexical_repository_compat");
                diagnostics.put("retrieveMethod", RetrieveMethod.KEYWORD.name());
            } else {
                RetrievalResult result = retrievalFacade.retrieve(
                        query, effectiveFilters, candidateSize, RetrieveMethod.HYBRID, true);
                documents = result.documents();
                if (result.diagnostics() != null) {
                    diagnostics.putAll(result.diagnostics());
                }
                diagnostics.put("retrieveMethod", RetrieveMethod.HYBRID.name());
            }
            List<MemoryEntry> entries = documents.stream()
                    .filter(this::isMemoryDocument)
                    .filter(this::isEnabledMemoryDocument)
                    .map(this::toMemoryEntry)
                    .limit(resultLimit)
                    .toList();
            diagnostics.put("memorySearch", true);
            diagnostics.put("memoryIsolationFilter", "source_type=memory");
            diagnostics.put("memoryResultCount", entries.size());
            recordMetric("search", "success", entries.size());
            audit("search", "success", "memory search completed", startedAt, Map.of(
                    "resultCount", entries.size(), "topK", resultLimit,
                    "filterCount", effectiveFilters.size()));
            return new MemorySearchResult(entries, diagnostics);
        } catch (RuntimeException ex) {
            recordMetric("search", "failed", 0);
            audit("search", "failed", "memory search failed", startedAt, Map.of(
                    "topK", resultLimit, "filterCount", effectiveFilters.size(),
                    "errorType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    public MemoryCleanupResult cleanupStale(Instant now, int scanLimit) {
        return cleanupStale(now, scanLimit, false);
    }

    public MemoryCleanupResult cleanupStale(Instant now, int scanLimit, boolean dryRun) {
        Instant startedAt = Instant.now();
        Instant reference = now == null ? Instant.now() : now;
        int limit = Math.max(1, scanLimit);
        int staleAfterDays = Math.max(1, properties.getMemory().getStaleAfterDays());
        Instant threshold = reference.minus(Duration.ofDays(staleAfterDays));
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            recordMetric("cleanup", "disabled", 0);
            MemoryCleanupResult result = new MemoryCleanupResult(
                    0, 0, 0, "disabled", limit, threshold, staleAfterDays, dryRun);
            auditCleanup(result, startedAt);
            return result;
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
        MemoryCleanupResult result = new MemoryCleanupResult(
                candidates.size(), eligible, deleted, status, limit, threshold, staleAfterDays, dryRun);
        auditCleanup(result, startedAt);
        return result;
    }

    public MemoryRestoreResult restore(String memoryId, Instant now) {
        Instant startedAt = Instant.now();
        Instant restoredAt = now == null ? Instant.now() : now;
        String normalizedId = memoryId == null ? "" : memoryId.trim();
        if (normalizedId.isBlank()) {
            return auditedRestore(new MemoryRestoreResult(
                    normalizedId, "invalid_id", null, null), startedAt);
        }
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            return auditedRestore(new MemoryRestoreResult(
                    normalizedId, "disabled", null, null), startedAt);
        }
        KnowledgeDocument document = knowledgeRepository.findById(normalizedId).orElse(null);
        if (document == null) {
            return auditedRestore(new MemoryRestoreResult(
                    normalizedId, "not_found", null, null), startedAt);
        }
        if (!isMemoryDocument(document)) {
            return auditedRestore(new MemoryRestoreResult(
                    normalizedId, "not_memory", null, null), startedAt);
        }
        Map<String, String> metadata = new LinkedHashMap<>(document.metadata());
        String previousDeleteReason = metadata.get("delete_reason");
        if (!"false".equalsIgnoreCase(metadata.get("memory_enabled"))) {
            return auditedRestore(new MemoryRestoreResult(
                    normalizedId, "already_active", null, previousDeleteReason), startedAt);
        }
        metadata.put("memory_enabled", "true");
        metadata.put("chunk_enable", "true");
        metadata.put("restored_at", restoredAt.toString());
        metadata.put("updated_at", restoredAt.toString());
        metadata.remove("deleted_at");
        metadata.remove("delete_reason");
        metadata.remove("duplicate_of");
        knowledgeRepository.save(new KnowledgeDocument(
                document.id(), document.title(), document.content(), document.source(),
                metadata, document.createdAt(), document.embeddingText(), document.embedding()));
        return auditedRestore(new MemoryRestoreResult(
                normalizedId, "success", restoredAt, previousDeleteReason), startedAt);
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

    private boolean isMemoryDocument(KnowledgeDocument document) {
        if (document == null) {
            return false;
        }
        if ("memory".equalsIgnoreCase(document.source())) {
            return true;
        }
        return document.metadata() != null
                && "memory".equalsIgnoreCase(document.metadata().get("source_type"));
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

    private void auditCleanup(MemoryCleanupResult result, Instant startedAt) {
        audit("cleanup", result.status(), "memory cleanup " + result.status(), startedAt, Map.of(
                "scanned", result.scanned(),
                "eligible", result.eligible(),
                "deleted", result.deleted(),
                "dryRun", result.dryRun(),
                "scanLimit", result.scanLimit()));
    }

    private MemoryRestoreResult auditedRestore(MemoryRestoreResult result, Instant startedAt) {
        recordMetric("restore", result.status(), "success".equals(result.status()) ? 1 : 0);
        audit("restore", result.status(), "memory restore " + result.status(), startedAt, Map.of(
                "memoryId", result.memoryId(),
                "previousDeleteReason", result.previousDeleteReason() == null ? "" : result.previousDeleteReason()));
        return result;
    }

    private void audit(String operation,
                       String status,
                       String summary,
                       Instant startedAt,
                       Map<String, Object> metadata) {
        if (auditService == null) {
            return;
        }
        try {
            auditService.recordMemoryOperation(operation, status, summary, startedAt, metadata);
        } catch (RuntimeException ignored) {
            // Audit failure must not change the maintenance operation result.
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

    public record MemorySearchResult(
            List<MemoryEntry> entries,
            Map<String, Object> diagnostics
    ) {
        public MemorySearchResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }
    }

    public record MemoryRestoreResult(
            String memoryId,
            String status,
            Instant restoredAt,
            String previousDeleteReason
    ) {
    }
}
