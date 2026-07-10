package com.kubeoncall.memory;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryServiceTest {

    @Test
    void shouldPersistMemoryAsIsolatedKnowledgeDocument() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        MemoryService service = new MemoryService(repository, new KubeOnCallProperties(), metricsService);
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
                Map.of("env", "prod")
        );

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
    void shouldSearchOnlyMemoryDocuments() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        MemoryService service = new MemoryService(repository, new KubeOnCallProperties(), metricsService);
        KnowledgeDocument document = new KnowledgeDocument(
                "memory-2",
                "payment pitfall",
                "avoid restart before checking queue lag",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "KNOWN_PITFALL",
                        "memory_scope", "SERVICE",
                        "service", "payment-service"
                ),
                Instant.now()
        );
        KnowledgeDocument disabled = new KnowledgeDocument(
                "memory-disabled",
                "deleted incident",
                "old incident",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "memory_scope", "SERVICE",
                        "memory_enabled", "false"
                ),
                Instant.now()
        );
        when(repository.searchLexical(any(RetrievalRequest.class), eq(6))).thenReturn(List.of(disabled, document));

        List<MemoryEntry> entries = service.search("payment restart", Map.of("service", "payment-service"), 2);

        assertEquals(1, entries.size());
        assertEquals(MemoryType.KNOWN_PITFALL, entries.get(0).type());
        ArgumentCaptor<RetrievalRequest> requestCaptor = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(repository).searchLexical(requestCaptor.capture(), eq(6));
        assertEquals("memory", requestCaptor.getValue().filters().get("source_type"));
        assertEquals("payment-service", requestCaptor.getValue().filters().get("service"));
        verify(metricsService).recordMemory("search", "success", 1);
    }

    @Test
    void shouldSoftDeleteOnlyStaleIncidentMemoriesAndKeepStableFacts() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setStaleAfterDays(30);
        MemoryService service = new MemoryService(repository, properties, metricsService);
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
                        "updated_at", "2026-05-01T00:00:00Z"
                ),
                Instant.parse("2026-05-01T00:00:00Z")
        );
        KnowledgeDocument fresh = new KnowledgeDocument(
                "memory-fresh",
                "fresh incident",
                "fresh content",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "memory_scope", "SERVICE",
                        "updated_at", "2026-07-01T00:00:00Z"
                ),
                Instant.parse("2026-07-01T00:00:00Z")
        );
        KnowledgeDocument staleStableFact = new KnowledgeDocument(
                "memory-stable",
                "stable owner fact",
                "payment is owned by team-payments",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "SERVICE_FACT",
                        "memory_scope", "SERVICE",
                        "updated_at", "2026-05-01T00:00:00Z"
                ),
                Instant.parse("2026-05-01T00:00:00Z")
        );
        when(repository.searchLexical(any(RetrievalRequest.class), eq(10)))
                .thenReturn(List.of(stale, fresh, staleStableFact));

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
    }

    @Test
    void cleanupDryRunShouldReportEligibleWithoutWriting() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setStaleAfterDays(30);
        MemoryService service = new MemoryService(repository, properties, metricsService);
        KnowledgeDocument stale = new KnowledgeDocument(
                "memory-old", "old incident", "old content", "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "INCIDENT_SUMMARY",
                        "updated_at", "2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z"));
        when(repository.searchLexical(any(RetrievalRequest.class), eq(10))).thenReturn(List.of(stale));

        MemoryService.MemoryCleanupResult result = service.cleanupStale(
                Instant.parse("2026-07-08T00:00:00Z"), 10, true);

        assertEquals(1, result.eligible());
        assertEquals(0, result.deleted());
        assertEquals("dry_run", result.status());
        assertEquals(true, result.dryRun());
        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(any());
        verify(metricsService).recordMemory("cleanup", "dry_run", 1);
    }
}
