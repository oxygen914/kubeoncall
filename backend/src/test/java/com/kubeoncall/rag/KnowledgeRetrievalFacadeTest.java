package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.domain.rag.RetrieveMethod;
import com.kubeoncall.rag.repository.KnowledgeRepository;

class KnowledgeRetrievalFacadeTest {

    @Test
    void shouldHideDiagnosticsWhenTraceIsDisabled() {
        Fixture fixture = new Fixture();

        RetrievalResult result = fixture.facade.retrieve("cpu alert", Map.of(), 5, RetrieveMethod.HYBRID, false);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                List.of("doc-1"),
                result.documents().stream().map(KnowledgeDocument::id).toList());
    }

    @Test
    void shouldExposeDiagnosticsOnlyWhenTraceIsEnabled() {
        Fixture fixture = new Fixture();

        RetrievalResult result = fixture.facade.retrieve("cpu alert", Map.of(), 5, RetrieveMethod.HYBRID, true);

        assertFalse(result.diagnostics().isEmpty());
        assertEquals("retrieval-secret", result.diagnostics().get("retrievalTrace"));
        assertEquals("rerank-secret", result.diagnostics().get("rerankTrace"));
        assertEquals("cpu alert", result.diagnostics().get("rawQuery"));
    }

    private static class Fixture {
        private final KnowledgeRepository repository = mock(KnowledgeRepository.class);
        private final QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        private final RagRouter router = mock(RagRouter.class);
        private final HybridRetrievalService retrievalService = mock(HybridRetrievalService.class);
        private final RerankService rerankService = mock(RerankService.class);
        private final KnowledgeRetrievalFacade facade;

        private Fixture() {
            KnowledgeDocument document =
                    new KnowledgeDocument("doc-1", "CPU runbook", "check cpu", "runbook", Map.of(), Instant.now());
            when(rewriteService.rewrite("cpu alert")).thenReturn("cpu alert");
            when(router.route("cpu alert")).thenReturn("RAG");
            when(retrievalService.retrieveWithTrace(any()))
                    .thenReturn(new HybridRetrievalService.RetrievalTrace(
                            List.of(document), List.of("retrieved"), Map.of("retrievalTrace", "retrieval-secret")));
            when(rerankService.rerank("cpu alert", List.of(document)))
                    .thenReturn(
                            new RerankService.RerankTrace(List.of(document), Map.of("rerankTrace", "rerank-secret")));
            facade = new KnowledgeRetrievalFacade(
                    repository, rewriteService, router, retrievalService, rerankService, new KubeOnCallProperties());
        }
    }
}
