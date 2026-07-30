package com.kubeoncall.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.KnowledgeRetrievalFacade;
import com.kubeoncall.tool.mcp.McpClient;

class LocalSopEvidenceSearchTest {

    @Test
    void shouldExposeVersionedRunbookAsRealLocalRagEvidence() {
        KnowledgeRetrievalFacade retrieval = mock(KnowledgeRetrievalFacade.class);
        KnowledgeDocument document = new KnowledgeDocument(
                "doc-pending-v2",
                "Pod Pending SOP",
                "Inspect FailedScheduling events before proposing a change.",
                "runbook",
                Map.of(
                        "runbookId", "runbook-cluster-capacity",
                        "runbook_version", "v2",
                        "source_type", "runbook"),
                Instant.parse("2026-07-30T00:00:00Z"));
        when(retrieval.retrieve(eq("why pending"), anyMap(), eq(3), any(), eq(true)))
                .thenReturn(new RetrievalResult(
                        "why pending", List.of(document), "hybrid", "retrieved", List.of(), Map.of()));

        Map<String, Object> result = new LocalSopEvidenceSearch(retrieval).search("why pending");

        assertThat(result)
                .containsEntry("collectionStatus", "SUCCEEDED")
                .containsEntry("simulation", false)
                .containsEntry("transport", "LOCAL_RAG")
                .containsEntry("sopId", "runbook-cluster-capacity")
                .containsEntry("version", "v2")
                .containsEntry("documentId", "doc-pending-v2");
        assertThat(result.get("snippet")).asString().contains("FailedScheduling");
    }

    @Test
    void shouldApplyExactMetadataFiltersForAnExplicitRunbookReference() {
        KnowledgeRetrievalFacade retrieval = mock(KnowledgeRetrievalFacade.class);
        when(retrieval.retrieve(
                        eq("analyse node with runbook-node-notready@v2"),
                        eq(Map.of(
                                "source_type",
                                "runbook",
                                "runbookId",
                                "runbook-node-notready",
                                "dataset_version",
                                "v2")),
                        eq(3),
                        any(),
                        eq(true)))
                .thenReturn(new RetrievalResult(
                        "analyse node",
                        List.of(new KnowledgeDocument(
                                "doc-node-v2",
                                "Node NotReady SOP",
                                "Inspect Ready condition and Lease.",
                                "runbook",
                                Map.of("runbookId", "runbook-node-notready", "runbook_version", "v2"),
                                Instant.parse("2026-07-30T00:00:00Z"))),
                        "hybrid",
                        "retrieved",
                        List.of(),
                        Map.of()));

        Map<String, Object> result =
                new LocalSopEvidenceSearch(retrieval).search("analyse node with runbook-node-notready@v2");

        assertThat(result)
                .containsEntry("collectionStatus", "SUCCEEDED")
                .containsEntry("sopId", "runbook-node-notready")
                .containsEntry("version", "v2");
        verify(retrieval)
                .retrieve(
                        eq("analyse node with runbook-node-notready@v2"),
                        eq(Map.of(
                                "source_type",
                                "runbook",
                                "runbookId",
                                "runbook-node-notready",
                                "dataset_version",
                                "v2")),
                        eq(3),
                        any(),
                        eq(true));
    }

    @Test
    void shouldPreferCurrentUserRunbookOverAStaleContextReference() {
        KnowledgeRetrievalFacade retrieval = mock(KnowledgeRetrievalFacade.class);
        String request =
                "Memory: runbook-pod-crashloop@v2\n" + "Current user: analyse node with runbook-node-notready@v2";
        when(retrieval.retrieve(
                        eq(request),
                        eq(Map.of(
                                "source_type",
                                "runbook",
                                "runbookId",
                                "runbook-node-notready",
                                "dataset_version",
                                "v2")),
                        eq(3),
                        any(),
                        eq(true)))
                .thenReturn(new RetrievalResult(
                        request,
                        List.of(new KnowledgeDocument(
                                "doc-node-v2",
                                "Node NotReady SOP",
                                "Inspect Ready condition and Lease.",
                                "runbook",
                                Map.of("runbookId", "runbook-node-notready", "runbook_version", "v2"),
                                Instant.parse("2026-07-30T00:00:00Z"))),
                        "hybrid",
                        "retrieved",
                        List.of(),
                        Map.of()));

        Map<String, Object> result = new LocalSopEvidenceSearch(retrieval).search(request);

        assertThat(result)
                .containsEntry("collectionStatus", "SUCCEEDED")
                .containsEntry("sopId", "runbook-node-notready")
                .containsEntry("version", "v2");
    }

    @Test
    void shouldReportEmptyInsteadOfInventingAnSop() {
        KnowledgeRetrievalFacade retrieval = mock(KnowledgeRetrievalFacade.class);
        when(retrieval.retrieve(eq("unknown"), anyMap(), eq(3), any(), eq(true)))
                .thenReturn(new RetrievalResult("unknown", List.of(), "hybrid", "empty", List.of(), Map.of()));

        Map<String, Object> result = new LocalSopEvidenceSearch(retrieval).search("unknown");

        assertThat(result)
                .containsEntry("collectionStatus", "EMPTY")
                .containsEntry("simulation", false)
                .containsEntry("errorType", "NO_MATCHING_SOP");
    }

    @Test
    void shouldUseLocalRagWhenExternalMcpKnowledgeToolIsUnavailable() {
        McpClient mcpClient = mock(McpClient.class);
        when(mcpClient.call(any(), anyMap()))
                .thenReturn(Map.of("status", "failed", "errorType", "DEPENDENCY_UNAVAILABLE"));
        LocalSopEvidenceSearch localSearch = mock(LocalSopEvidenceSearch.class);
        when(localSearch.search("analyse pending"))
                .thenReturn(Map.of(
                        "tool",
                        "knowledge.searchSop",
                        "source",
                        "knowledge.rag.local",
                        "collectionStatus",
                        "SUCCEEDED",
                        "simulation",
                        false,
                        "sopId",
                        "runbook-cluster-capacity",
                        "version",
                        "v2"));

        PlannerToolEvidenceCollector.Evidence evidence =
                new PlannerToolEvidenceCollector(mcpClient, null, localSearch).collect("analyse pending", List.of());

        assertThat(evidence.payload().get("sop"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("collectionStatus", "SUCCEEDED")
                .containsEntry("source", "knowledge.rag.local")
                .containsEntry("simulation", false);
        verify(localSearch).search("analyse pending");
    }
}
