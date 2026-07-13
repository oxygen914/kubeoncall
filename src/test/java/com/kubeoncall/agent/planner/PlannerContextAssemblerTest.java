package com.kubeoncall.agent.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;

class PlannerContextAssemblerTest {

    private final PlannerContextAssembler assembler = new PlannerContextAssembler();

    @Test
    void shouldComposeNormalizedPlanningRequestFromSkillMemoryAndSessionContext() {
        GraphState state = new GraphState();
        state.getContext().put("skillPrompt", "Skill: inspect before changing");
        state.getContext().put("memoryContext", "Memory: pod restarted yesterday");
        state.getContext().put("sessionContext", "Session: payment-service is production");

        String request = assembler.planningRequest(state, " restart payment-service ");

        assertEquals(
                "Skill: inspect before changing\nMemory: pod restarted yesterday\n"
                        + "Session: payment-service is production\nCurrent user:  restart payment-service ",
                request);
        assertEquals("restart payment-service", assembler.normalizeRequest(" restart   payment-service "));
    }

    @Test
    void shouldMergeSkillKnowledgeAndCollectToolEvidence() {
        GraphState state = new GraphState();
        state.getContext().put("plannerKnowledge", Map.of("metrics", Map.of("tool", "prometheus")));
        state.getContext().put("activatedSkillIds", List.of("payment-oom"));
        state.getContext()
                .put(
                        "plannerAvailableTools",
                        List.of(Map.of("name", "prometheus.query"), Map.of("name", "kubernetes.logs")));

        Map<String, Object> knowledge = assembler.plannerKnowledge(state);

        assertEquals(List.of("payment-oom"), knowledge.get("activatedSkillIds"));
        assertEquals(Map.of("metrics", "prometheus"), assembler.evidenceSources(knowledge));
        assertEquals(List.of("prometheus.query", "kubernetes.logs"), assembler.consultedTools(state));
    }
}
