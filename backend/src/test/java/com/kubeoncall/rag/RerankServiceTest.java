package com.kubeoncall.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.service.KubeOnCallMetricsService;

class RerankServiceTest {

    @Test
    void shouldPrioritizeHigherScoreThenNewerDocument() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setRerankTopN(10);
        RerankService rerankService = new RerankService(properties, List.of(), mock(KubeOnCallMetricsService.class));

        KnowledgeDocument lowScore = new KnowledgeDocument(
                "doc-low",
                "network guide",
                "latency troubleshooting",
                "manual",
                Map.of(),
                Instant.parse("2026-04-18T10:00:00Z"));
        KnowledgeDocument highScoreOld = new KnowledgeDocument(
                "doc-high-old",
                "payment timeout runbook",
                "payment timeout mitigation",
                "manual",
                Map.of("service", "payment"),
                Instant.parse("2026-04-17T10:00:00Z"));
        KnowledgeDocument highScoreNew = new KnowledgeDocument(
                "doc-high-new",
                "payment timeout playbook",
                "payment timeout mitigation latest",
                "manual",
                Map.of("service", "payment"),
                Instant.parse("2026-04-19T10:00:00Z"));

        RerankService.RerankTrace trace =
                rerankService.rerank("payment timeout", List.of(lowScore, highScoreOld, highScoreNew));

        assertEquals(
                List.of("doc-high-new", "doc-high-old", "doc-low"),
                trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertTrue(trace.diagnostics().containsKey("scoreByDocument"));
        assertEquals(
                List.of("doc-high-new", "doc-high-old", "doc-low"),
                trace.diagnostics().get("rerankedDocumentIds"));
        assertEquals("rule_overlap", trace.diagnostics().get("rerankStrategy"));
        assertTrue(trace.diagnostics().get("rankTrace") instanceof List<?> rankTrace && rankTrace.size() == 3);
        assertEquals(false, trace.diagnostics().get("crossEncoderApplied"));
    }

    @Test
    void shouldLimitByRerankTopNAndExposeFallbackDiagnostics() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        properties.getRag().setRerankTopN(1);
        RerankService rerankService = new RerankService(properties, List.of(), mock(KubeOnCallMetricsService.class));

        KnowledgeDocument docA =
                new KnowledgeDocument("doc-a", "payment timeout", "runbook", "manual", Map.of(), Instant.now());
        KnowledgeDocument docB = new KnowledgeDocument("doc-b", "payment", "retry", "manual", Map.of(), Instant.now());

        RerankService.RerankTrace trace = rerankService.rerank("payment timeout", List.of(docA, docB));

        assertEquals(1, trace.documents().size());
        assertEquals(true, trace.diagnostics().get("crossEncoderFallback"));
        assertEquals(true, trace.diagnostics().containsKey("crossEncoderFallbackReason"));
        assertEquals("retrieval_order_fallback", trace.diagnostics().get("rerankStrategy"));
        assertEquals("doc-a", trace.documents().get(0).id());
    }

    @Test
    void shouldUseCrossEncoderAsPrimaryRankingPath() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        CrossEncoderReranker reranker = new CrossEncoderReranker() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Map<String, Double> score(String query, List<KnowledgeDocument> documents) {
                return Map.of("doc-a", 0.1, "doc-b", 0.9);
            }
        };
        RerankService service = new RerankService(properties, List.of(reranker), mock(KubeOnCallMetricsService.class));
        KnowledgeDocument docA =
                new KnowledgeDocument("doc-a", "timeout exact keyword", "timeout", "manual", Map.of(), Instant.now());
        KnowledgeDocument docB = new KnowledgeDocument("doc-b", "other", "other", "manual", Map.of(), Instant.now());

        RerankService.RerankTrace trace = service.rerank("timeout", List.of(docA, docB));

        assertEquals(
                List.of("doc-b", "doc-a"),
                trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertEquals("cross_encoder", trace.diagnostics().get("rerankStrategy"));
        assertEquals(false, trace.diagnostics().get("crossEncoderFallback"));
    }

    @Test
    void shouldHandleNullTitleAndContent() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        RerankService rerankService = new RerankService(properties, List.of(), mock(KubeOnCallMetricsService.class));

        KnowledgeDocument document = new KnowledgeDocument("doc-null", null, null, "manual", Map.of(), Instant.now());

        RerankService.RerankTrace trace = rerankService.rerank("timeout", List.of(document));

        assertEquals(1, trace.documents().size());
        assertEquals("doc-null", trace.documents().get(0).id());
    }
}
