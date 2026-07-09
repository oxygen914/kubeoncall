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
        when(repository.searchLexical(any(RetrievalRequest.class), eq(2))).thenReturn(List.of(document));

        List<MemoryEntry> entries = service.search("payment restart", Map.of("service", "payment-service"), 2);

        assertEquals(1, entries.size());
        assertEquals(MemoryType.KNOWN_PITFALL, entries.get(0).type());
        ArgumentCaptor<RetrievalRequest> requestCaptor = ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(repository).searchLexical(requestCaptor.capture(), eq(2));
        assertEquals("memory", requestCaptor.getValue().filters().get("source_type"));
        assertEquals("payment-service", requestCaptor.getValue().filters().get("service"));
        verify(metricsService).recordMemory("search", "success", 1);
    }

    @Test
    void shouldCleanupOnlyStaleMemoryDocuments() {
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
        when(repository.searchLexical(any(RetrievalRequest.class), eq(10))).thenReturn(List.of(stale, fresh));

        MemoryService.MemoryCleanupResult result = service.cleanupStale(now, 10);

        assertEquals(2, result.scanned());
        assertEquals(1, result.deleted());
        assertEquals(10, result.scanLimit());
        assertEquals(30, result.staleAfterDays());
        assertEquals(Instant.parse("2026-06-08T00:00:00Z"), result.staleThreshold());
        verify(repository).deleteById("memory-old");
        verify(metricsService).recordMemory("cleanup", "success", 1);
    }
}
