package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankServiceTest {

    @Test
    void shouldPrioritizeHigherScoreThenNewerDocument() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setRerankTopN(10);
        RerankService rerankService = new RerankService(properties);

        KnowledgeDocument lowScore = new KnowledgeDocument(
                "doc-low",
                "network guide",
                "latency troubleshooting",
                "manual",
                Map.of(),
                Instant.parse("2026-04-18T10:00:00Z")
        );
        KnowledgeDocument highScoreOld = new KnowledgeDocument(
                "doc-high-old",
                "payment timeout runbook",
                "payment timeout mitigation",
                "manual",
                Map.of("service", "payment"),
                Instant.parse("2026-04-17T10:00:00Z")
        );
        KnowledgeDocument highScoreNew = new KnowledgeDocument(
                "doc-high-new",
                "payment timeout playbook",
                "payment timeout mitigation latest",
                "manual",
                Map.of("service", "payment"),
                Instant.parse("2026-04-19T10:00:00Z")
        );

        RerankService.RerankTrace trace = rerankService.rerank(
                "payment timeout",
                List.of(lowScore, highScoreOld, highScoreNew)
        );

        assertEquals(List.of("doc-high-new", "doc-high-old", "doc-low"),
                trace.documents().stream().map(KnowledgeDocument::id).toList());
        assertTrue(trace.diagnostics().containsKey("scoreByDocument"));
        assertEquals(false, trace.diagnostics().get("crossEncoderApplied"));
    }

    @Test
    void shouldLimitByRerankTopNAndExposeFallbackDiagnostics() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setCrossEncoderEnabled(true);
        properties.getRag().setRerankTopN(1);
        RerankService rerankService = new RerankService(properties);

        KnowledgeDocument docA = new KnowledgeDocument("doc-a", "payment timeout", "runbook", "manual", Map.of(), Instant.now());
        KnowledgeDocument docB = new KnowledgeDocument("doc-b", "payment", "retry", "manual", Map.of(), Instant.now());

        RerankService.RerankTrace trace = rerankService.rerank("payment timeout", List.of(docA, docB));

        assertEquals(1, trace.documents().size());
        assertEquals(true, trace.diagnostics().get("crossEncoderFallback"));
        assertEquals(true, trace.diagnostics().containsKey("crossEncoderFallbackReason"));
    }

    @Test
    void shouldHandleNullTitleAndContent() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        RerankService rerankService = new RerankService(properties);

        KnowledgeDocument document = new KnowledgeDocument(
                "doc-null",
                null,
                null,
                "manual",
                Map.of(),
                Instant.now()
        );

        RerankService.RerankTrace trace = rerankService.rerank("timeout", List.of(document));

        assertEquals(1, trace.documents().size());
        assertEquals("doc-null", trace.documents().get(0).id());
    }
}
