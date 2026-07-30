package com.kubeoncall.agent.planner;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.skill.SkillExecutionPolicy;
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
        return select(SkillExecutionPolicy.ToolAccess.unrestricted());
    }

    public Selection select(SkillExecutionPolicy.ToolAccess toolAccess) {
        SkillExecutionPolicy.ToolAccess access =
                toolAccess == null ? SkillExecutionPolicy.ToolAccess.unrestricted() : toolAccess;
        List<Map<String, Object>> tools = agentToolCatalog.plannerTools().stream()
                .filter(tool -> access.allows(tool.name()))
                .map(this::toPayload)
                .toList();
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
