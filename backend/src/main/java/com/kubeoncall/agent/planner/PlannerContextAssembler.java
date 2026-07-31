package com.kubeoncall.agent.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillExecutionPolicy;

@Component
public class PlannerContextAssembler {

    public String planningRequest(GraphState state, String currentRequest) {
        String sessionText = contextText(state, "sessionContext");
        String memoryText = contextText(state, "memoryContext");
        String skillText = contextText(state, "skillPrompt");
        String ragText = contextText(state, "ragContext");
        if (sessionText.isBlank() && memoryText.isBlank() && skillText.isBlank() && ragText.isBlank()) {
            return currentRequest;
        }
        StringBuilder builder = new StringBuilder();
        appendContext(builder, skillText);
        appendContext(builder, memoryText);
        appendContext(builder, ragText);
        appendContext(builder, sessionText);
        builder.append("\nCurrent user: ").append(currentRequest == null ? "" : currentRequest);
        return builder.toString();
    }

    public String normalizeRequest(String request) {
        return request == null ? "" : request.trim().replaceAll("\\s+", " ");
    }

    public Map<String, Object> plannerKnowledge(GraphState state) {
        Object value = state.getContext().get("plannerKnowledge");
        Map<String, Object> knowledge = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            raw.forEach((key, entryValue) -> knowledge.put(String.valueOf(key), entryValue));
        }
        mergeSkillKnowledge(knowledge, state);
        return knowledge;
    }

    public void applySkillActivation(GraphState state, SkillActivation activation) {
        state.getContext().put("activatedSkills", activation.skillSummaries());
        state.getContext().put("activatedSkillIds", activation.skillIds());
        state.getContext().put("activatedSkillMatchSources", activation.matchSources());
        state.getContext().put("skillPrompt", activation.prompt());
        state.getContext().put("activatedSkillToolWhitelist", activation.toolWhitelist());
        if (activation.maxRisk() != null) {
            state.getContext().put("activatedSkillMaxRisk", activation.maxRisk().name());
        }
        Map<String, Object> knowledge = new LinkedHashMap<>(plannerKnowledge(state));
        knowledge.put("activatedSkills", activation.skillSummaries());
        knowledge.put("activatedSkillIds", activation.skillIds());
        knowledge.put("activatedSkillMatchSources", activation.matchSources());
        knowledge.put("activatedSkillToolWhitelist", activation.toolWhitelist());
        knowledge.put(
                "activatedSkillMaxRisk",
                activation.maxRisk() == null ? null : activation.maxRisk().name());
        knowledge.put("skillPrompt", activation.prompt());
        state.getContext().put("plannerKnowledge", knowledge);
    }

    public List<String> consultedTools(GraphState state) {
        Object value = state.getContext().get("plannerAvailableTools");
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(tool -> String.valueOf(tool.get("name")))
                    .toList();
        }
        return List.of();
    }

    public Map<String, String> evidenceSources(Map<String, Object> plannerKnowledge) {
        Map<String, String> evidence = new LinkedHashMap<>();
        plannerKnowledge.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map && map.containsKey("tool")) {
                evidence.put(key, String.valueOf(map.get("tool")));
            }
        });
        return evidence;
    }

    public List<String> missingSignals(GraphState state) {
        Object value = state.getContext().get("plannerMissingSignals");
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(entry -> !entry.isBlank())
                    .toList();
        }
        return List.of();
    }

    public SkillExecutionPolicy.ToolAccess toolAccess(GraphState state) {
        return SkillExecutionPolicy.toolAccess(state == null ? null : state.getContext());
    }

    public void attachSkillKnowledge(Map<String, Object> knowledge, GraphState state) {
        mergeSkillKnowledge(knowledge, state);
    }

    private String contextText(GraphState state, String key) {
        Object value = state.getContext().get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void appendContext(StringBuilder builder, String value) {
        if (value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n");
        }
        builder.append(value);
    }

    private void mergeSkillKnowledge(Map<String, Object> knowledge, GraphState state) {
        putIfPresent(knowledge, state, "activatedSkills");
        putIfPresent(knowledge, state, "activatedSkillIds");
        putIfPresent(knowledge, state, "activatedSkillMatchSources");
        putIfPresent(knowledge, state, "activatedSkillToolWhitelist");
        putIfPresent(knowledge, state, "activatedSkillMaxRisk");
        putIfPresent(knowledge, state, "skillPrompt");
        putIfPresent(knowledge, state, "ragContext");
    }

    private void putIfPresent(Map<String, Object> knowledge, GraphState state, String key) {
        Object value = state.getContext().get(key);
        if (value != null) {
            knowledge.putIfAbsent(key, value);
        }
    }
}
