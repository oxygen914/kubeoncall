package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalHit;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;

class HybridRetrievalServiceTest {

    @Test
    void shouldReturnTraceWithDiagnostics() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setLexicalCandidateTopN(5);
        properties.getRag().setVectorCandidateTopN(5);
        properties.getRag().setRrfK(60);
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of("env", "lab"), 3);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        KnowledgeDocument vector = new KnowledgeDocument("doc-2", "vt", "vc", "manual", Map.of(), Instant.now());
        when(repository.searchLexicalHits(request, 50))
                .thenReturn(List.of(new RetrievalHit(lexical, 4.2, 1, "LEXICAL")));
        VectorRetriever vectorRetriever = vectorRetriever(List.of(vector));
        HybridRetrievalService service = new HybridRetrievalService(
                repository, properties, List.of(vectorRetriever), mock(KubeOnCallMetricsService.class));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        verify(repository).searchLexicalHits(request, 50);
        assertEquals(2, trace.documents().size());
        assertTrue(trace.reasons().stream().anyMatch(reason -> reason.contains("metadata filters")));
        assertTrue(trace.reasons().stream().anyMatch(reason -> reason.contains("reciprocal rank fusion")));
        assertEquals(1, trace.diagnostics().get("filterCount"));
        assertEquals(3, trace.diagnostics().get("topK"));
        assertEquals(1, trace.diagnostics().get("lexicalCandidateCount"));
        assertEquals(1, trace.diagnostics().get("vectorCandidateCount"));
        assertEquals(false, trace.diagnostics().get("vectorFallback"));
        assertEquals("reciprocal_rank_fusion", trace.diagnostics().get("rankingSource"));
        assertEquals("test_vector", trace.diagnostics().get("vectorSource"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lexicalHits =
                (List<Map<String, Object>>) trace.diagnostics().get("lexicalHits");
        assertEquals(4.2, lexicalHits.get(0).get("rawScore"));
        assertEquals("LEXICAL", lexicalHits.get(0).get("channel"));
        assertTrue(trace.diagnostics().containsKey("lexicalLatencyMs"));
        assertTrue(trace.diagnostics().containsKey("vectorLatencyMs"));
        assertTrue(trace.diagnostics().containsKey("fusionLatencyMs"));
    }

    @Test
    void shouldFallbackToLexicalWhenVectorDisabled() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(false);
        properties.getRag().setLexicalCandidateTopN(4);
        HybridRetrievalService service =
                new HybridRetrievalService(repository, properties, List.of(), mock(KubeOnCallMetricsService.class));
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of(), 2);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        when(repository.searchLexicalHits(request, 50))
                .thenReturn(List.of(new RetrievalHit(lexical, 2.0, 1, "LEXICAL")));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        verify(repository).searchLexicalHits(request, 50);
        assertEquals(
                List.of("doc-1"),
                trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals(true, trace.reasons().stream().anyMatch(reason -> reason.contains("disabled")));
        assertEquals(true, trace.diagnostics().get("vectorEnabled").equals(false));
        assertEquals("lexical_repository_hit_order", trace.diagnostics().get("rankingSource"));
    }

    @Test
    void keywordModeShouldNotCallVectorRetrieval() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setLexicalCandidateTopN(4);
        HybridRetrievalService service =
                new HybridRetrievalService(repository, properties, List.of(), mock(KubeOnCallMetricsService.class));
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of(), 2, RetrieveMethod.KEYWORD, true);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        when(repository.searchLexicalHits(request, 50))
                .thenReturn(List.of(new RetrievalHit(lexical, 2.0, 1, "LEXICAL")));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        verify(repository).searchLexicalHits(request, 50);
        verify(repository, never()).searchVector(request, 50);
        assertEquals(
                List.of("doc-1"),
                trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals("KEYWORD", trace.diagnostics().get("retrieveMethod"));
        assertEquals(true, trace.diagnostics().get("includeTrace"));
    }

    @Test
    void failedVectorRetrieverShouldFallbackToLexical() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setLexicalCandidateTopN(4);
        properties.getRag().setVectorCandidateTopN(4);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        VectorRetriever failingRetriever = new VectorRetriever() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<KnowledgeDocument> retrieve(RetrievalRequest request, int candidateSize) {
                throw new IllegalStateException("embedding service unavailable");
            }

            @Override
            public String source() {
                return "failing_mock";
            }
        };
        HybridRetrievalService service =
                new HybridRetrievalService(repository, properties, List.of(failingRetriever), metricsService);
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of(), 2);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        when(repository.searchLexicalHits(request, 50))
                .thenReturn(List.of(new RetrievalHit(lexical, 2.0, 1, "LEXICAL")));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        assertEquals(
                List.of("doc-1"),
                trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals(true, trace.diagnostics().get("vectorFallback"));
        assertEquals("Vector retrieval unavailable", trace.diagnostics().get("vectorFallbackReason"));
        verify(metricsService).recordRagRetrieval("HYBRID", "fallback", true, 1, (Long)
                trace.diagnostics().get("latencyMs"));
    }

    private VectorRetriever vectorRetriever(List<KnowledgeDocument> documents) {
        return new VectorRetriever() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<KnowledgeDocument> retrieve(RetrievalRequest request, int candidateSize) {
                return documents;
            }

            @Override
            public String source() {
                return "test_vector";
            }
        };
    }
}
