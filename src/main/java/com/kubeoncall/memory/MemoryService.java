package com.kubeoncall.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.KnowledgeRetrievalFacade;
import com.kubeoncall.rag.repository.KnowledgeRepository;

@Service
public class MemoryService {

    private final KnowledgeRepository knowledgeRepository;
    private final KubeOnCallProperties properties;
    private final KnowledgeRetrievalFacade retrievalFacade;
    private final MemoryTemporalNormalizer temporalNormalizer;
    private final MemoryDocumentMapper documentMapper;
    private final MemoryOperationObserver operationObserver;

    public MemoryService(
            KnowledgeRepository knowledgeRepository,
            KubeOnCallProperties properties,
            KnowledgeRetrievalFacade retrievalFacade,
            MemoryTemporalNormalizer temporalNormalizer,
            MemoryDocumentMapper documentMapper,
            MemoryOperationObserver operationObserver) {
        this.knowledgeRepository = knowledgeRepository;
        this.properties = properties;
        this.retrievalFacade = retrievalFacade;
        this.temporalNormalizer = temporalNormalizer;
        this.documentMapper = documentMapper;
        this.operationObserver = operationObserver;
    }

    public MemoryEntry remember(MemoryEntry entry) {
        Instant startedAt = Instant.now();
        if (entry == null) {
            throw new IllegalArgumentException("memory entry must not be null");
        }
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            operationObserver.recordMetric("remember", "disabled", 0);
            operationObserver.recordAudit(
                    "remember",
                    "disabled",
                    "memory remember disabled",
                    startedAt,
                    Map.of("memoryId", entry.id() == null ? "" : entry.id()));
            return entry;
        }
        try {
            MemoryEntry normalized = normalizeTemporal(entry);
            knowledgeRepository.save(documentMapper.toKnowledgeDocument(normalized));
            operationObserver.recordMetric("remember", "success", 1);
            operationObserver.recordAudit(
                    "remember",
                    "success",
                    "memory remembered",
                    startedAt,
                    Map.of(
                            "memoryId", normalized.id() == null ? "" : normalized.id(),
                            "memoryType",
                                    normalized.type() == null
                                            ? ""
                                            : normalized.type().name(),
                            "memoryScope",
                                    normalized.scope() == null
                                            ? ""
                                            : normalized.scope().name()));
            return normalized;
        } catch (RuntimeException ex) {
            operationObserver.recordMetric("remember", "failed", 0);
            operationObserver.recordAudit(
                    "remember",
                    "failed",
                    "memory remember failed",
                    startedAt,
                    Map.of(
                            "memoryId",
                            entry.id() == null ? "" : entry.id(),
                            "errorType",
                            ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private MemoryEntry normalizeTemporal(MemoryEntry entry) {
        try {
            MemoryEntry normalized = temporalNormalizer.normalize(entry, Instant.now());
            if (!normalized.equals(entry)) {
                operationObserver.recordMetric("normalize", "success", 1);
            }
            return normalized;
        } catch (RuntimeException ex) {
            operationObserver.recordMetric("normalize", "failed", 1);
            return entry;
        }
    }

    public List<MemoryEntry> search(String query, Map<String, String> filters, int topK) {
        return searchWithTrace(query, filters, topK).entries();
    }

    public MemorySearchResult searchWithTrace(String query, Map<String, String> filters, int topK) {
        Instant startedAt = Instant.now();
        if (!properties.getMemory().isEnabled()) {
            operationObserver.recordMetric("search", "disabled", 0);
            MemorySearchResult result =
                    new MemorySearchResult(List.of(), Map.of("memorySearch", true, "status", "disabled"));
            operationObserver.recordAudit(
                    "search", "disabled", "memory search disabled", startedAt, Map.of("topK", Math.max(1, topK)));
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
            RetrievalResult result =
                    retrievalFacade.retrieve(query, effectiveFilters, candidateSize, RetrieveMethod.HYBRID, true);
            documents = result.documents();
            if (result.diagnostics() != null) {
                diagnostics.putAll(result.diagnostics());
            }
            diagnostics.put("retrieveMethod", RetrieveMethod.HYBRID.name());
            List<MemoryEntry> entries = documents.stream()
                    .filter(documentMapper::isMemoryDocument)
                    .filter(documentMapper::isEnabled)
                    .map(documentMapper::toMemoryEntry)
                    .limit(resultLimit)
                    .toList();
            diagnostics.put("memorySearch", true);
            diagnostics.put("memoryIsolationFilter", "source_type=memory");
            diagnostics.put("memoryResultCount", entries.size());
            operationObserver.recordMetric("search", "success", entries.size());
            operationObserver.recordAudit(
                    "search",
                    "success",
                    "memory search completed",
                    startedAt,
                    Map.of("resultCount", entries.size(), "topK", resultLimit, "filterCount", effectiveFilters.size()));
            return new MemorySearchResult(entries, diagnostics);
        } catch (RuntimeException ex) {
            operationObserver.recordMetric("search", "failed", 0);
            operationObserver.recordAudit(
                    "search",
                    "failed",
                    "memory search failed",
                    startedAt,
                    Map.of(
                            "topK",
                            resultLimit,
                            "filterCount",
                            effectiveFilters.size(),
                            "errorType",
                            ex.getClass().getSimpleName()));
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
            operationObserver.recordMetric("cleanup", "disabled", 0);
            MemoryCleanupResult result =
                    new MemoryCleanupResult(0, 0, 0, "disabled", limit, threshold, staleAfterDays, dryRun);
            operationObserver.recordCleanup(result, startedAt);
            return result;
        }
        RetrievalRequest request = new RetrievalRequest("", Map.of("source_type", "memory"), limit);
        List<KnowledgeDocument> candidates = knowledgeRepository.searchLexical(request, limit);

        int eligible = 0;
        int deleted = 0;
        for (KnowledgeDocument document : candidates) {
            if (documentMapper.isCleanupEligible(document, threshold)) {
                eligible++;
                if (dryRun) {
                    continue;
                }
                knowledgeRepository.save(documentMapper.softDeleted(document, reference));
                deleted++;
            }
        }
        String status = dryRun ? "dry_run" : "success";
        operationObserver.recordMetric("cleanup", status, dryRun ? eligible : deleted);
        MemoryCleanupResult result = new MemoryCleanupResult(
                candidates.size(), eligible, deleted, status, limit, threshold, staleAfterDays, dryRun);
        operationObserver.recordCleanup(result, startedAt);
        return result;
    }

    public MemoryRestoreResult restore(String memoryId, Instant now) {
        Instant startedAt = Instant.now();
        Instant restoredAt = now == null ? Instant.now() : now;
        String normalizedId = memoryId == null ? "" : memoryId.trim();
        if (normalizedId.isBlank()) {
            return auditedRestore(new MemoryRestoreResult(normalizedId, "invalid_id", null, null), startedAt);
        }
        if (!properties.getMemory().isEnabled() || !properties.getMemory().isLongTermEnabled()) {
            return auditedRestore(new MemoryRestoreResult(normalizedId, "disabled", null, null), startedAt);
        }
        KnowledgeDocument document = knowledgeRepository.findById(normalizedId).orElse(null);
        if (document == null) {
            return auditedRestore(new MemoryRestoreResult(normalizedId, "not_found", null, null), startedAt);
        }
        if (!documentMapper.isMemoryDocument(document)) {
            return auditedRestore(new MemoryRestoreResult(normalizedId, "not_memory", null, null), startedAt);
        }
        Map<String, String> metadata = new LinkedHashMap<>(document.metadata());
        String previousDeleteReason = metadata.get("delete_reason");
        if (!"false".equalsIgnoreCase(metadata.get("memory_enabled"))) {
            return auditedRestore(
                    new MemoryRestoreResult(normalizedId, "already_active", null, previousDeleteReason), startedAt);
        }
        metadata.put("memory_enabled", "true");
        metadata.put("chunk_enable", "true");
        metadata.put("restored_at", restoredAt.toString());
        metadata.put("updated_at", restoredAt.toString());
        metadata.remove("deleted_at");
        metadata.remove("delete_reason");
        metadata.remove("duplicate_of");
        knowledgeRepository.save(new KnowledgeDocument(
                document.id(),
                document.title(),
                document.content(),
                document.source(),
                metadata,
                document.createdAt(),
                document.embeddingText(),
                document.embedding()));
        return auditedRestore(
                new MemoryRestoreResult(normalizedId, "success", restoredAt, previousDeleteReason), startedAt);
    }

    private MemoryRestoreResult auditedRestore(MemoryRestoreResult result, Instant startedAt) {
        operationObserver.recordRestore(result, startedAt);
        return result;
    }

    public record MemoryCleanupResult(
            int scanned,
            int eligible,
            int deleted,
            String status,
            int scanLimit,
            Instant staleThreshold,
            int staleAfterDays,
            boolean dryRun) {}

    public record MemorySearchResult(List<MemoryEntry> entries, Map<String, Object> diagnostics) {
        public MemorySearchResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        }
    }

    public record MemoryRestoreResult(
            String memoryId, String status, Instant restoredAt, String previousDeleteReason) {}
}
