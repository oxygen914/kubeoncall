package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.domain.rag.RetrievalRequest;
import com.kubeoncall.rag.repository.KnowledgeRepository;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrievalServiceTest {

    @Test
    void shouldReturnTraceWithDiagnostics() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setLexicalCandidateTopN(5);
        properties.getRag().setVectorCandidateTopN(5);
        properties.getRag().setRrfK(60);
        HybridRetrievalService service = new HybridRetrievalService(repository, properties);
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of("env", "lab"), 3);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        KnowledgeDocument vector = new KnowledgeDocument("doc-2", "vt", "vc", "manual", Map.of(), Instant.now());
        when(repository.searchLexical(request, 5)).thenReturn(List.of(lexical));
        when(repository.searchVector(request, 5)).thenReturn(List.of(vector));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        verify(repository).searchLexical(request, 5);
        verify(repository).searchVector(request, 5);
        assertEquals(2, trace.documents().size());
        assertTrue(trace.reasons().stream().anyMatch(reason -> reason.contains("metadata filters")));
        assertTrue(trace.reasons().stream().anyMatch(reason -> reason.contains("reciprocal rank fusion")));
        assertEquals(1, trace.diagnostics().get("filterCount"));
        assertEquals(3, trace.diagnostics().get("topK"));
        assertEquals(1, trace.diagnostics().get("lexicalCandidateCount"));
        assertEquals(1, trace.diagnostics().get("vectorCandidateCount"));
        assertEquals(false, trace.diagnostics().get("vectorFallback"));
    }

    @Test
    void shouldFallbackToLexicalWhenVectorDisabled() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(false);
        properties.getRag().setLexicalCandidateTopN(4);
        HybridRetrievalService service = new HybridRetrievalService(repository, properties);
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of(), 2);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        when(repository.searchLexical(request, 4)).thenReturn(List.of(lexical));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        verify(repository).searchLexical(request, 4);
        assertEquals(List.of("doc-1"), trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals(true, trace.reasons().stream().anyMatch(reason -> reason.contains("disabled")));
        assertEquals(true, trace.diagnostics().get("vectorEnabled").equals(false));
    }

    @Test
    void keywordModeShouldNotCallVectorRetrieval() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setVectorEnabled(true);
        properties.getRag().setLexicalCandidateTopN(4);
        HybridRetrievalService service = new HybridRetrievalService(repository, properties);
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of(), 2, RetrieveMethod.KEYWORD, true);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        when(repository.searchLexical(request, 4)).thenReturn(List.of(lexical));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        verify(repository).searchLexical(request, 4);
        verify(repository, never()).searchVector(request, 4);
        assertEquals(List.of("doc-1"), trace.documents().stream().map(KnowledgeDocument::id).toList());
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
        HybridRetrievalService service = new HybridRetrievalService(repository, properties, List.of(failingRetriever), metricsService);
        RetrievalRequest request = new RetrievalRequest("payment timeout", Map.of(), 2);

        KnowledgeDocument lexical = new KnowledgeDocument("doc-1", "t", "c", "manual", Map.of(), Instant.now());
        when(repository.searchLexical(request, 4)).thenReturn(List.of(lexical));

        HybridRetrievalService.RetrievalTrace trace = service.retrieveWithTrace(request);

        assertEquals(List.of("doc-1"), trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals(true, trace.diagnostics().get("vectorFallback"));
        assertEquals("embedding service unavailable", trace.diagnostics().get("vectorFallbackReason"));
        verify(metricsService).recordRagRetrieval("HYBRID", "fallback", true, 1, (Long) trace.diagnostics().get("latencyMs"));
    }
}
