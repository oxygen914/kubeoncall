package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;

class ContextCompressorTest {

    @Test
    void shouldBoundPlanningContextAndRuntimeObservations() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setContextTokenBudget(120);
        properties.getMemory().setObservationTokenBudget(80);
        properties.getMemory().setMaxObservationEntries(10);
        TokenBudget tokenBudget = new TokenBudget();
        ContextCompressor compressor = new ContextCompressor(properties, tokenBudget);
        GraphState state = new GraphState();
        String large = "payment-service 诊断上下文 ".repeat(120);
        state.getContext().put("skillPrompt", large);
        state.getContext().put("memoryContext", large);
        state.getContext().put("sessionContext", large);
        state.getContext().put("plannerKnowledge", new LinkedHashMap<>(Map.of("skillPrompt", large)));

        compressor.compressPlanningContext(state);

        int planningTokens = tokenBudget.estimateTokens(
                        String.valueOf(state.getContext().get("skillPrompt")))
                + tokenBudget.estimateTokens(String.valueOf(state.getContext().get("memoryContext")))
                + tokenBudget.estimateTokens(String.valueOf(state.getContext().get("sessionContext")));
        assertTrue(planningTokens <= 120);
        @SuppressWarnings("unchecked")
        Map<String, Object> plannerKnowledge =
                (Map<String, Object>) state.getContext().get("plannerKnowledge");
        assertEquals(state.getContext().get("skillPrompt"), plannerKnowledge.get("skillPrompt"));

        for (int index = 0; index < 50; index++) {
            state.addObservation(
                    index == 0
                            ? "payment-service failed health check"
                            : "observation " + index + " " + "detail ".repeat(10));
        }
        compressor.compressRuntime(state);

        assertTrue(state.getObservations().size() <= 10);
        assertTrue(state.getObservations().get(0).contains("failed health check"));
        assertTrue(state.getObservations().stream()
                        .mapToInt(tokenBudget::estimateTokens)
                        .sum()
                <= 80);
    }
}
