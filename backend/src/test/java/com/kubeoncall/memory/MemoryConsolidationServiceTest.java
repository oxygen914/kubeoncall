package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;

class MemoryConsolidationServiceTest {

    @Test
    void shouldRequireMetricsDependency() {
        KubeOnCallProperties properties = new KubeOnCallProperties();

        assertThrows(
                NullPointerException.class,
                () -> new MemoryConsolidationService(
                        mock(KnowledgeRepository.class),
                        properties,
                        null,
                        mock(ExecutionAuditService.class),
                        new MemoryTemporalNormalizer(properties)));
    }

    @Test
    void shouldKeepNewestCanonicalAndSoftDeleteDuplicate() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setDuplicateSimilarityThreshold(0.8d);
        MemoryConsolidationService service = service(repository, properties, metrics, audit);
        KnowledgeDocument newest = memory("memory-new", "payment owner is team-payments", "2026-07-10T10:00:00Z");
        KnowledgeDocument duplicate = memory("memory-old", "payment owner: team-payments", "2026-07-01T10:00:00Z");
        KnowledgeDocument different = new KnowledgeDocument(
                "memory-other",
                "payment timeout",
                "payment timeout is 3s",
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "SERVICE_FACT",
                        "memory_scope", "SERVICE",
                        "service", "payment-service",
                        "subject", "payment timeout",
                        "memory_enabled", "true",
                        "updated_at", "2026-07-09T10:00:00Z"),
                Instant.parse("2026-07-09T10:00:00Z"));
        when(repository.searchLexical(any(RetrievalRequest.class), eq(50)))
                .thenReturn(List.of(duplicate, different, newest));

        MemoryConsolidationService.ConsolidationResult result =
                service.consolidate(Instant.parse("2026-07-10T12:00:00Z"), 50, false);

        assertEquals(3, result.scanned());
        assertEquals(1, result.duplicateGroups());
        assertEquals(1, result.eligible());
        assertEquals(1, result.consolidated());
        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(saved.capture());
        assertEquals("memory-old", saved.getValue().id());
        assertEquals("memory-new", saved.getValue().metadata().get("duplicate_of"));
        assertEquals("duplicate_consolidation", saved.getValue().metadata().get("delete_reason"));
        assertEquals("false", saved.getValue().metadata().get("memory_enabled"));
        verify(metrics).recordMemory("consolidate", "success", 1);
        verify(audit)
                .recordMemoryOperation(
                        eq("consolidate"), eq("success"), any(String.class), any(Instant.class), any(Map.class));
    }

    @Test
    void dryRunShouldReportDuplicatesWithoutWriting() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        MemoryConsolidationService service =
                service(repository, properties, metrics, mock(ExecutionAuditService.class));
        when(repository.searchLexical(any(RetrievalRequest.class), eq(20)))
                .thenReturn(List.of(
                        memory("memory-a", "same fact", "2026-07-10T10:00:00Z"),
                        memory("memory-b", "same fact", "2026-07-01T10:00:00Z")));

        MemoryConsolidationService.ConsolidationResult result =
                service.consolidate(Instant.parse("2026-07-10T12:00:00Z"), 20, true);

        assertEquals("dry_run", result.status());
        assertEquals(1, result.eligible());
        assertEquals(0, result.consolidated());
        verify(repository, never()).save(any());
        verify(metrics).recordMemory("consolidate", "dry_run", 1);
    }

    @Test
    void shouldNormalizeLegacyRelativeDatesAndKeepDryRunReadOnly() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        MemoryTemporalNormalizer normalizer = new MemoryTemporalNormalizer(properties);
        MemoryConsolidationService service = new MemoryConsolidationService(
                repository, properties, metrics, mock(ExecutionAuditService.class), normalizer);
        KnowledgeDocument legacy = memory("memory-relative", "昨天 payment-service OOM", "2026-07-10T01:00:00Z");
        when(repository.searchLexical(any(RetrievalRequest.class), eq(10))).thenReturn(List.of(legacy));

        MemoryConsolidationService.ConsolidationResult dryRun =
                service.consolidate(Instant.parse("2026-07-10T12:00:00Z"), 10, true);

        assertEquals(1, dryRun.normalizationEligible());
        assertEquals(0, dryRun.normalized());
        verify(repository, never()).save(any());

        MemoryConsolidationService.ConsolidationResult applied =
                service.consolidate(Instant.parse("2026-07-10T12:00:00Z"), 10, false);

        assertEquals(1, applied.normalizationEligible());
        assertEquals(1, applied.normalized());
        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository).save(saved.capture());
        assertEquals("2026-07-09 payment-service OOM", saved.getValue().content());
        assertEquals("normalized", saved.getValue().metadata().get("temporal_normalization_status"));
    }

    private MemoryConsolidationService service(
            KnowledgeRepository repository,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metrics,
            ExecutionAuditService audit) {
        return new MemoryConsolidationService(
                repository, properties, metrics, audit, new MemoryTemporalNormalizer(properties));
    }

    private KnowledgeDocument memory(String id, String content, String updatedAt) {
        return new KnowledgeDocument(
                id,
                "payment owner",
                content,
                "memory",
                Map.of(
                        "source_type", "memory",
                        "memory_type", "SERVICE_FACT",
                        "memory_scope", "SERVICE",
                        "service", "payment-service",
                        "subject", "payment owner",
                        "memory_enabled", "true",
                        "updated_at", updatedAt),
                Instant.parse(updatedAt));
    }
}
