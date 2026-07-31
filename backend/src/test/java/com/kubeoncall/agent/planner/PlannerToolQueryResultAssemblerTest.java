package com.kubeoncall.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.evidence.EvidenceCollectionStatus;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceResource;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.evidence.EvidenceWindow;

class PlannerToolQueryResultAssemblerTest {

    @Test
    void exposesBoundedDirectEvidenceToTheRealModelPlanner() {
        PlannerToolQueryResultAssembler assembler = new PlannerToolQueryResultAssembler(new PlannerContextAssembler());
        GraphState state = new GraphState();
        Instant now = Instant.now();
        state.getContext()
                .put(
                        "evidenceItems",
                        List.of(new EvidenceItem(
                                "evd_state",
                                "exe_1",
                                EvidenceType.RESOURCE_STATE,
                                "kubernetes-api",
                                "local",
                                "kubeoncall-system",
                                new EvidenceResource("Pod", "adapter-abc", "pod-uid"),
                                now,
                                new EvidenceWindow(now.minusSeconds(60), now),
                                "Pod/adapter-abc is Running",
                                "ready=true",
                                Map.of(),
                                0,
                                true,
                                false,
                                "sha256:test",
                                EvidenceCollectionStatus.SUCCEEDED,
                                "",
                                "",
                                Map.of())));

        NodeResult result = assembler.assemble(
                state,
                "inspect adapter-abc",
                new PlannerToolEvidenceCollector.Evidence("adapter-abc", Map.of()),
                new PlannerToolCandidates.Selection(List.of(), true));

        List<?> collected = (List<?>) result.payload().get("collectedEvidence");
        assertThat(collected).hasSize(1);
        Map<?, ?> summary = (Map<?, ?>) collected.get(0);
        assertThat(summary.get("source")).isEqualTo("kubernetes-api");
        assertThat(summary.get("collectionStatus")).isEqualTo("SUCCEEDED");
        assertThat(summary.get("summary")).isEqualTo("Pod/adapter-abc is Running");
        assertThat(state.getContext().get("plannerKnowledge")).isEqualTo(result.payload());
    }
}
