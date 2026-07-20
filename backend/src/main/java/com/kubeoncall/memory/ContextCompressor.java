package com.kubeoncall.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;

@Component
public class ContextCompressor {

    private static final List<String> PLANNING_CONTEXT_KEYS =
            List.of("skillPrompt", "memoryContext", "ragContext", "sessionContext");

    private final KubeOnCallProperties properties;
    private final TokenBudget tokenBudget;

    public ContextCompressor(KubeOnCallProperties properties, TokenBudget tokenBudget) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
    }

    public void compressPlanningContext(GraphState state) {
        if (state == null) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : PLANNING_CONTEXT_KEYS) {
            Object value = state.getContext().get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                values.put(key, String.valueOf(value));
            }
        }
        int beforeTokens =
                values.values().stream().mapToInt(tokenBudget::estimateTokens).sum();
        int budget = Math.max(96, properties.getMemory().getContextTokenBudget());
        if (beforeTokens <= budget || values.isEmpty()) {
            state.getContext().put("contextCompression", compressionTrace(beforeTokens, beforeTokens, List.of()));
            enforceUnifiedContextBudget(state);
            return;
        }

        int perSectionBudget = Math.max(1, budget / values.size());
        List<String> compressedKeys = new ArrayList<>();
        values.forEach((key, value) -> {
            String compressed = tokenBudget.compactText(value, perSectionBudget);
            state.getContext().put(key, compressed);
            if (!compressed.equals(value)) {
                compressedKeys.add(key);
            }
        });
        syncPlannerKnowledge(state);
        int afterTokens = PLANNING_CONTEXT_KEYS.stream()
                .map(state.getContext()::get)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .mapToInt(tokenBudget::estimateTokens)
                .sum();
        state.getContext().put("contextCompression", compressionTrace(beforeTokens, afterTokens, compressedKeys));
        enforceUnifiedContextBudget(state);
    }

    public void compressRuntime(GraphState state) {
        if (state == null) {
            return;
        }
        if (state.getObservations().isEmpty()) {
            enforceUnifiedContextBudget(state);
            return;
        }
        int budget = Math.max(64, properties.getMemory().getObservationTokenBudget());
        int maxEntries = Math.max(2, properties.getMemory().getMaxObservationEntries());
        int beforeTokens = state.getObservations().stream()
                .mapToInt(tokenBudget::estimateTokens)
                .sum();
        if (state.getObservations().size() <= maxEntries && beforeTokens <= budget) {
            enforceUnifiedContextBudget(state);
            return;
        }

        List<String> original = List.copyOf(state.getObservations());
        int maxRecentByBudget = Math.max(1, (budget * 2 / 3) / 8);
        int recentCount = Math.min(Math.min(maxEntries - 1, Math.max(1, original.size() / 3)), maxRecentByBudget);
        List<String> early = original.subList(0, original.size() - recentCount);
        List<String> recent = original.subList(original.size() - recentCount, original.size());
        String summary = summarizeObservations(early, Math.max(24, budget / 3));
        int recentBudget = Math.max(1, (budget - tokenBudget.estimateTokens(summary)) / recent.size());
        List<String> compressed = new ArrayList<>();
        compressed.add(summary);
        recent.stream()
                .map(value -> tokenBudget.compactText(value, recentBudget))
                .forEach(compressed::add);
        state.getObservations().clear();
        state.getObservations().addAll(compressed);
        int afterTokens =
                compressed.stream().mapToInt(tokenBudget::estimateTokens).sum();
        state.getContext()
                .put(
                        "observationCompression",
                        Map.of(
                                "compressed",
                                true,
                                "originalEntries",
                                original.size(),
                                "remainingEntries",
                                compressed.size(),
                                "beforeTokens",
                                beforeTokens,
                                "afterTokens",
                                afterTokens));
        enforceUnifiedContextBudget(state);
    }

    /**
     * Keeps the textual state that can reach the planner under one shared cap. Planning context
     * and observations used to be compressed independently, which allowed their sum to exceed the
     * LLM context contract.
     */
    private void enforceUnifiedContextBudget(GraphState state) {
        int budget = Math.max(128, properties.getMemory().getUnifiedContextTokenBudget());
        int planningBefore = planningTokens(state);
        int observationsBefore = observationTokens(state);
        int before = planningBefore + observationsBefore;
        if (before <= budget) {
            state.getContext().put("unifiedContextBudget", unifiedTrace(before, before, budget, false));
            return;
        }

        int observationReserve = state.getObservations().isEmpty() ? 0 : Math.max(32, budget / 4);
        compactPlanningToBudget(state, Math.max(32, budget - observationReserve));

        int remainingForObservations = Math.max(16, budget - planningTokens(state));
        compactObservationsToBudget(state, remainingForObservations);

        int after = planningTokens(state) + observationTokens(state);
        if (after > budget) {
            forceCompactAllContextSections(state, budget);
            after = planningTokens(state) + observationTokens(state);
        }
        state.getContext().put("unifiedContextBudget", unifiedTrace(before, after, budget, true));
    }

    private void forceCompactAllContextSections(GraphState state, int budget) {
        List<String> populatedKeys = PLANNING_CONTEXT_KEYS.stream()
                .filter(key -> state.getContext().get(key) != null)
                .filter(key -> !String.valueOf(state.getContext().get(key)).isBlank())
                .toList();
        int sectionCount = populatedKeys.size() + (state.getObservations().isEmpty() ? 0 : 1);
        if (sectionCount == 0) {
            return;
        }
        int perSectionBudget = Math.max(1, budget / sectionCount);
        for (String key : populatedKeys) {
            state.getContext()
                    .put(
                            key,
                            tokenBudget.compactText(
                                    String.valueOf(state.getContext().get(key)), perSectionBudget));
        }
        if (!state.getObservations().isEmpty()) {
            List<String> original = List.copyOf(state.getObservations());
            state.getObservations().clear();
            state.getObservations().add(summarizeObservations(original, perSectionBudget));
        }
        syncPlannerKnowledge(state);
    }

    private void compactPlanningToBudget(GraphState state, int budget) {
        List<String> populatedKeys = PLANNING_CONTEXT_KEYS.stream()
                .filter(key -> state.getContext().get(key) != null)
                .filter(key -> !String.valueOf(state.getContext().get(key)).isBlank())
                .toList();
        if (populatedKeys.isEmpty() || planningTokens(state) <= budget) {
            return;
        }
        int perSectionBudget = Math.max(1, budget / populatedKeys.size());
        for (String key : populatedKeys) {
            state.getContext()
                    .put(
                            key,
                            tokenBudget.compactText(
                                    String.valueOf(state.getContext().get(key)), perSectionBudget));
        }
        syncPlannerKnowledge(state);
    }

    private void compactObservationsToBudget(GraphState state, int budget) {
        if (state.getObservations().isEmpty() || observationTokens(state) <= budget) {
            return;
        }
        List<String> original = List.copyOf(state.getObservations());
        state.getObservations().clear();
        state.getObservations().add(summarizeObservations(original, Math.max(1, budget)));
    }

    private int planningTokens(GraphState state) {
        return PLANNING_CONTEXT_KEYS.stream()
                .map(state.getContext()::get)
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .mapToInt(tokenBudget::estimateTokens)
                .sum();
    }

    private int observationTokens(GraphState state) {
        return state.getObservations().stream()
                .mapToInt(tokenBudget::estimateTokens)
                .sum();
    }

    private Map<String, Object> unifiedTrace(int before, int after, int budget, boolean compacted) {
        return Map.of(
                "compressed", compacted,
                "beforeTokens", before,
                "afterTokens", after,
                "budgetTokens", budget,
                "countingMode", tokenBudget.countingMode());
    }

    private String summarizeObservations(List<String> observations, int budget) {
        List<String> keyEvents = observations.stream()
                .filter(this::isKeyEvent)
                .map(value -> tokenBudget.compactText(value, 48))
                .distinct()
                .limit(8)
                .toList();
        StringBuilder builder =
                new StringBuilder("[compressed ").append(observations.size()).append(" earlier observations]");
        keyEvents.forEach(event -> builder.append("\n- ").append(event));
        return tokenBudget.compactText(builder.toString(), budget);
    }

    private boolean isKeyEvent(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("fail")
                || normalized.contains("error")
                || normalized.contains("approved")
                || normalized.contains("rejected")
                || normalized.contains("service")
                || normalized.contains("告警")
                || normalized.contains("失败")
                || normalized.contains("审批");
    }

    @SuppressWarnings("unchecked")
    private void syncPlannerKnowledge(GraphState state) {
        Object raw = state.getContext().get("plannerKnowledge");
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>();
        map.forEach((key, value) -> updated.put(String.valueOf(key), value));
        for (String key : PLANNING_CONTEXT_KEYS) {
            if (state.getContext().containsKey(key)) {
                updated.put(key, state.getContext().get(key));
            }
        }
        state.getContext().put("plannerKnowledge", updated);
    }

    private Map<String, Object> compressionTrace(int before, int after, List<String> keys) {
        return Map.of(
                "compressed",
                after < before,
                "beforeTokens",
                before,
                "afterTokens",
                after,
                "budgetTokens",
                Math.max(96, properties.getMemory().getContextTokenBudget()),
                "compressedKeys",
                List.copyOf(keys));
    }
}
