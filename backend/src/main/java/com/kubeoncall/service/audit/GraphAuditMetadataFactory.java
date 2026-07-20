package com.kubeoncall.service.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.graph.GraphState;

@Component
public class GraphAuditMetadataFactory {

    public Map<String, Object> build(GraphState state) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> context = state.getContext();
        putIfPresent(metadata, "sessionId", context.get("sessionId"));
        putIfPresent(metadata, "plannerSource", context.get("plannerSource"));
        putIfPresent(metadata, "plannerIntent", context.get("plannerIntent"));
        putIfPresent(metadata, "plannerConfidence", context.get("plannerConfidence"));
        putIfPresent(metadata, "verifierDecision", context.get("verifierDecision"));
        putIfPresent(metadata, "verifierRiskReasons", context.get("verifierRiskReasons"));
        putIfPresent(metadata, "verifierTool", context.get("verifierTool"));
        putIfPresent(metadata, "activatedSkillIds", context.get("activatedSkillIds"));
        putIfPresent(metadata, "activatedSkillMaxRisk", context.get("activatedSkillMaxRisk"));
        putIfPresent(metadata, "activatedSkillToolWhitelist", context.get("activatedSkillToolWhitelist"));
        putIfPresent(metadata, "skillToolWhitelistViolation", context.get("skillToolWhitelistViolation"));
        putIfPresent(metadata, "injectedMemoryCount", context.get("injectedMemoryCount"));
        putIfPresent(metadata, "memoryWarning", context.get("memoryWarning"));
        putIfPresent(metadata, "memoryExtractionWarning", context.get("memoryExtractionWarning"));
        putIfPresent(metadata, "retryTraceSize", retryCount(state));
        putIfPresent(metadata, "nodeResultCount", state.getNodeResults().size());
        putTaskMetadata(metadata, state);
        putExecutorMetadata(metadata, context);
        return metadata;
    }

    public int retryCount(GraphState state) {
        Object retryTrace = state.getContext().get("retryTrace");
        return retryTrace instanceof List<?> list ? list.size() : 0;
    }

    private void putTaskMetadata(Map<String, Object> metadata, GraphState state) {
        if (state.getTaskPlan() != null && state.getTaskPlan().tasks() != null) {
            List<String> taskIds = state.getTaskPlan().tasks().stream()
                    .map(task -> task.taskId())
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            putIfPresent(metadata, "taskCount", state.getTaskPlan().tasks().size());
            putIfPresent(metadata, "taskIds", taskIds);
            putIfPresent(metadata, "planApprovalRequired", state.getTaskPlan().approvalRequired());
        }
        if (state.getCurrentTask() == null) {
            return;
        }
        putIfPresent(metadata, "currentTaskId", state.getCurrentTask().taskId());
        putIfPresent(metadata, "currentTaskType", state.getCurrentTask().taskType());
        putIfPresent(metadata, "currentTaskRisk", state.getCurrentTask().riskLevel());
        putIfPresent(metadata, "currentTaskTarget", state.getCurrentTask().target());
    }

    private void putExecutorMetadata(Map<String, Object> metadata, Map<String, Object> context) {
        Object executorPayload = context.get("executorPayload");
        if (executorPayload instanceof Map<?, ?> map) {
            putIfPresent(metadata, "executorKind", map.get("executorKind"));
            putIfPresent(metadata, "executorAction", map.get("action"));
            putIfPresent(metadata, "executorToolName", map.get("toolName"));
            putIfPresent(metadata, "executorDryRun", map.get("dryRun"));
        }
        Object executorResult = context.get("executorResult");
        if (executorResult instanceof Map<?, ?> map) {
            putIfPresent(metadata, "executorResultStatus", map.get("status"));
            putIfPresent(metadata, "executorResultMessage", map.get("message"));
        }
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
