package com.kubeoncall.agent.planner;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;

/** Produces the normalized, read-only planner tool candidate view used in graph state. */
@Component
public class PlannerToolCandidates {

    private final AgentToolCatalog agentToolCatalog;

    public PlannerToolCandidates(AgentToolCatalog agentToolCatalog) {
        this.agentToolCatalog = agentToolCatalog;
    }

    public Selection select() {
        List<Map<String, Object>> tools =
                agentToolCatalog.plannerTools().stream().map(this::toPayload).toList();
        return new Selection(tools, agentToolCatalog.isPlannerReadOnly());
    }

    private Map<String, Object> toPayload(ToolDefinition tool) {
        return Map.of(
                "name", tool.name(),
                "executorKind", tool.executorKind(),
                "description", tool.description(),
                "readOnly", tool.readOnly(),
                "requiredParameters", tool.requiredParameters(),
                "targetSystems", tool.targetSystems());
    }

    public record Selection(List<Map<String, Object>> tools, boolean readOnlyValidated) {

        public Selection {
            tools = List.copyOf(tools);
        }
    }
}
