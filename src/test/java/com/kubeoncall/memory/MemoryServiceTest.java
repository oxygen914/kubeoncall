package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.KnowledgeRetrievalFacade;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;

class MemoryServiceTest {

    @Test
    void shouldPersistMemoryAsIsolatedKnowledgeDocument() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        MemoryService service = service(repository, new KubeOnCallProperties(), metricsService);
        MemoryEntry entry = new MemoryEntry(
                "memory-1",
                MemoryType.SERVICE_FACT,
                MemoryScope.SERVICE,
                "payment owner",
                "payment-service is owned by team-payments",
                "payment-service",
                null,
                null,
                Instant.now(),
                Instant.now(),
                Map.of("env", "prod"));

        service.remember(entry);

        ArgumentCaptor<KnowledgeDocument> documentCaptor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(documentCaptor.capture());
        KnowledgeDocument document = documentCaptor.getValue();
        assertEquals("memory", document.source());
        assertEquals("memory", document.metadata().get("source_type"));
        assertEquals("SERVICE_FACT", document.metadata().get("memory_type"));
        assertEquals("SERVICE", document.metadata().get("memory_scope"));
        assertEquals("payment-service", document.metadata().get("service"));
        assertEquals("true", document.metadata().get("memory_enabled"));
        verify(metricsService).recordMemory("remember", "success", 1);
    }

    @Test
    void shouldAuditRememberAndSearchOperations() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KnowledgeRetrievalFacade retrievalFacade = mock(KnowledgeRetrievalFacade.class);
        MemoryService service =
                service(repository, properties, mock(KubeOnCallMetricsService.class), retrievalFacade, auditService);
        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry(
                "memory-audit",
                MemoryType.SERVICE_FACT,
                MemoryScope.SERVICE,
                "payment owner",
                "team-payments",
                "payment-service",
                null,
                null,
                now,
                now,
                Map.of());
        KnowledgeDocument document = new KnowledgeDocument(
                "memory-audit",
                "payment owner",
                "team-payments",
                "memory",
                Map.of(
                        "source_type",
                        "memory",
                        "memory_type",
                        "SERVICE_FACT",
                        "memory_scope",
                        "SERVICE",
                        "memory_enabled",
                        "true"),
                now);
        when(retrievalFacade.retrieve(eq("payment owner"), any(), eq(3), eq(RetrieveMethod.HYBRID), eq(true)))
                .thenReturn(retrievalResult("payment owner", List.of(document)));

        service.remember(entry);
        service.searchWithTrace("payment owner", Map.of("service", "payment-service"), 1);

        verify(auditService)
                .recordMemoryOperation(eq("remember"), eq("success"), any(), any(Instant.class), any(Map.class));
        verify(auditService)
                .recordMemoryOperation(eq("search"), eq("success"), any(), any(Instant.class), any(Map.class));
    }

    @Test
    void shouldNormalizeRelativeDateOnEveryMemoryWritePath() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        MemoryTemporalNormalizer normalizer = new MemoryTemporalNormalizer(properties);
        MemoryService service = service(
                repository,
                properties,
                mock(KubeOnCallMetricsService.class),
                mock(KnowledgeRetrievalFacade.class),
                mock(ExecutionAuditService.class),
                normalizer);
        Instant createdAt = Instant.parse("2026-07-10T01:00:00Z");
        MemoryEntry entry = new MemoryEntry(
                "memory-date",
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.SERVICE,
                "payment incident",
                "昨天 payment-service OOM",
                "payment-service",
                null,
                null,
                createdAt,
                createdAt,
                Map.of("source", "alarm"));

        MemoryEntry remembered = service.remember(entry);

        assertEquals("2026-07-09 payment-service OOM", remembered.content());
        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(saved.capture());
        assertEquals(remembered.content(), saved.getValue().content());
        assertEquals("normalized", saved.getValue().metadata().get("temporal_normalization_status"));
    }

    @Test
    void shouldSearchOnlyMemoryDocuments() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KnowledgeRetrievalFacade retrievalFacade = mock(KnowledgeRetrievalFacade.class);
        MemoryService service =
                service(repository, properties, metricsService, retrievalFacade, mock(ExecutionAuditService.class));
        KnowledgeDocument document = new KnowledgeDocument(
                "memory-2",
                "payment pitfall",
                "avoid restart before checking queue lag",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "KNOWN_PITFALL",
                        "memory_scope", "SERVICE",
                        "service", "payment-service"),
                Instant.now());
        KnowledgeDocument disabled = new KnowledgeDocument(
                "memory-disabled",
                "deleted incident",
                "old incident",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "memory_scope", "SERVICE",
                        "memory_enabled", "false"),
                Instant.now());
        when(retrievalFacade.retrieve(eq("payment restart"), any(), eq(6), eq(RetrieveMethod.HYBRID), eq(true)))
                .thenReturn(retrievalResult("payment restart", List.of(disabled, document)));

        List<MemoryEntry> entries = service.search("payment restart", Map.of("service", "payment-service"), 2);

        assertEquals(1, entries.size());
        assertEquals(MemoryType.KNOWN_PITFALL, entries.get(0).type());
        ArgumentCaptor<Map<String, String>> filtersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(retrievalFacade)
                .retrieve(eq("payment restart"), filtersCaptor.capture(), eq(6), eq(RetrieveMethod.HYBRID), eq(true));
        assertEquals("memory", filtersCaptor.getValue().get("source_type"));
        assertEquals("payment-service", filtersCaptor.getValue().get("service"));
        verify(metricsService).recordMemory("search", "success", 1);
    }

    @Test
    void shouldReuseHybridRetrievalWithMandatoryMemoryIsolationAndTrace() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        KnowledgeRetrievalFacade retrievalFacade = mock(KnowledgeRetrievalFacade.class);
        MemoryService service = service(
                repository,
                new KubeOnCallProperties(),
                metricsService,
                retrievalFacade,
                mock(ExecutionAuditService.class));
        KnowledgeDocument memory = new KnowledgeDocument(
                "memory-hybrid",
                "payment owner",
                "team-payments",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "SERVICE_FACT",
                        "memory_scope", "SERVICE",
                        "memory_enabled", "true"),
                Instant.now());
        KnowledgeDocument sop = new KnowledgeDocument(
                "sop-1", "payment sop", "restart", "manual", Map.of("source_type", "sop"), Instant.now());
        when(retrievalFacade.retrieve(eq("payment owner"), any(), eq(6), eq(RetrieveMethod.HYBRID), eq(true)))
                .thenReturn(new RetrievalResult(
                        "payment owner",
                        List.of(sop, memory),
                        "RAG",
                        "ok",
                        List.of(),
                        Map.of("rankingSource", "reciprocal_rank_fusion")));

        MemoryService.MemorySearchResult result =
                service.searchWithTrace("payment owner", Map.of("service", "payment-service"), 2);

        assertEquals(
                List.of("memory-hybrid"),
                result.entries().stream().map(MemoryEntry::id).toList());
        assertEquals("reciprocal_rank_fusion", result.diagnostics().get("rankingSource"));
        assertEquals("source_type=memory", result.diagnostics().get("memoryIsolationFilter"));
        ArgumentCaptor<Map<String, String>> filtersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(retrievalFacade)
                .retrieve(eq("payment owner"), filtersCaptor.capture(), eq(6), eq(RetrieveMethod.HYBRID), eq(true));
        assertEquals("memory", filtersCaptor.getValue().get("source_type"));
        assertEquals("payment-service", filtersCaptor.getValue().get("service"));
        verify(repository, never()).searchLexical(any(), anyInt());
    }

    @Test
    void shouldSoftDeleteOnlyStaleIncidentMemoriesAndKeepStableFacts() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setStaleAfterDays(30);
        MemoryService service =
                service(repository, properties, metricsService, mock(KnowledgeRetrievalFacade.class), auditService);
        Instant now = Instant.parse("2026-07-08T00:00:00Z");
        KnowledgeDocument stale = new KnowledgeDocument(
                "memory-old",
                "old incident",
                "old content",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "memory_scope", "SERVICE",
                        "updated_at", "2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"));
        KnowledgeDocument fresh = new KnowledgeDocument(
                "memory-fresh",
                "fresh incident",
                "fresh content",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "memory_scope", "SERVICE",
                        "updated_at", "2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"));
        KnowledgeDocument staleStableFact = new KnowledgeDocument(
                "memory-stable",
                "stable owner fact",
                "payment is owned by team-payments",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "SERVICE_FACT",
                        "memory_scope", "SERVICE",
                        "updated_at", "2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"));
        when(repository.searchLexical(any(), eq(10))).thenReturn(List.of(stale, fresh, staleStableFact));

        MemoryService.MemoryCleanupResult result = service.cleanupStale(now, 10);

        assertEquals(3, result.scanned());
        assertEquals(1, result.eligible());
        assertEquals(1, result.deleted());
        assertEquals(10, result.scanLimit());
        assertEquals(30, result.staleAfterDays());
        assertEquals(Instant.parse("2026-06-08T00:00:00Z"), result.staleThreshold());
        ArgumentCaptor<KnowledgeDocument> softDeletedCaptor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(softDeletedCaptor.capture());
        assertEquals("memory-old", softDeletedCaptor.getValue().id());
        assertEquals("false", softDeletedCaptor.getValue().metadata().get("memory_enabled"));
        assertEquals("false", softDeletedCaptor.getValue().metadata().get("chunk_enable"));
        assertEquals("stale_cleanup", softDeletedCaptor.getValue().metadata().get("delete_reason"));
        verify(repository, never()).deleteById(any());
        verify(metricsService).recordMemory("cleanup", "success", 1);
        verify(auditService)
                .recordMemoryOperation(
                        eq("cleanup"), eq("success"), any(String.class), any(Instant.class), any(Map.class));
    }

    @Test
    void cleanupDryRunShouldReportEligibleWithoutWriting() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setStaleAfterDays(30);
        MemoryService service =
                service(repository, properties, metricsService, mock(KnowledgeRetrievalFacade.class), auditService);
        KnowledgeDocument stale = new KnowledgeDocument(
                "memory-old",
                "old incident",
                "old content",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "updated_at", "2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"));
        when(repository.searchLexical(any(), eq(10))).thenReturn(List.of(stale));

        MemoryService.MemoryCleanupResult result =
                service.cleanupStale(Instant.parse("2026-07-08T00:00:00Z"), 10, true);

        assertEquals(1, result.eligible());
        assertEquals(0, result.deleted());
        assertEquals("dry_run", result.status());
        assertEquals(true, result.dryRun());
        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(any());
        verify(metricsService).recordMemory("cleanup", "dry_run", 1);
        verify(auditService)
                .recordMemoryOperation(
                        eq("cleanup"), eq("dry_run"), any(String.class), any(Instant.class), any(Map.class));
    }

    @Test
    void shouldRestoreSoftDeletedMemoryAndClearDeletionMetadata() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        MemoryService service =
                service(repository, new KubeOnCallProperties(), metrics, mock(KnowledgeRetrievalFacade.class), audit);
        KnowledgeDocument deleted = new KnowledgeDocument(
                "memory-deleted",
                "payment owner",
                "team-payments",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "SERVICE_FACT",
                        "memory_enabled", "false",
                        "chunk_enable", "false",
                        "deleted_at", "2026-07-01T00:00:00Z",
                        "delete_reason", "duplicate_consolidation",
                        "duplicate_of", "memory-canonical"),
                Instant.parse("2026-06-01T00:00:00Z"));
        when(repository.findById("memory-deleted")).thenReturn(Optional.of(deleted));
        Instant restoredAt = Instant.parse("2026-07-10T12:00:00Z");

        MemoryService.MemoryRestoreResult result = service.restore("memory-deleted", restoredAt);

        assertEquals("success", result.status());
        assertEquals("duplicate_consolidation", result.previousDeleteReason());
        ArgumentCaptor<KnowledgeDocument> restored = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(restored.capture());
        assertEquals("true", restored.getValue().metadata().get("memory_enabled"));
        assertEquals("true", restored.getValue().metadata().get("chunk_enable"));
        assertEquals(restoredAt.toString(), restored.getValue().metadata().get("restored_at"));
        assertEquals(false, restored.getValue().metadata().containsKey("deleted_at"));
        assertEquals(false, restored.getValue().metadata().containsKey("delete_reason"));
        assertEquals(false, restored.getValue().metadata().containsKey("duplicate_of"));
        verify(metrics).recordMemory("restore", "success", 1);
        verify(audit)
                .recordMemoryOperation(
                        eq("restore"), eq("success"), any(String.class), any(Instant.class), any(Map.class));
    }

    private static MemoryService service(
            KnowledgeRepository repository, KubeOnCallProperties properties, KubeOnCallMetricsService metricsService) {
        return service(
                repository,
                properties,
                metricsService,
                mock(KnowledgeRetrievalFacade.class),
                mock(ExecutionAuditService.class));
    }

    private static MemoryService service(
            KnowledgeRepository repository,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            KnowledgeRetrievalFacade retrievalFacade,
            ExecutionAuditService auditService) {
        return service(
                repository,
                properties,
                metricsService,
                retrievalFacade,
                auditService,
                new MemoryTemporalNormalizer(properties));
    }

    private static MemoryService service(
            KnowledgeRepository repository,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            KnowledgeRetrievalFacade retrievalFacade,
            ExecutionAuditService auditService,
            MemoryTemporalNormalizer temporalNormalizer) {
        return new MemoryService(
                repository,
                properties,
                retrievalFacade,
                temporalNormalizer,
                new MemoryDocumentMapper(),
                new MemoryOperationObserver(metricsService, auditService));
    }

    private static RetrievalResult retrievalResult(String query, List<KnowledgeDocument> documents) {
        return new RetrievalResult(query, documents, "RAG", "ok", List.of(), Map.of());
    }
}
