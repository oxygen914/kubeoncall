package com.kubeoncall.workflow.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;

@Component
public class SilenceNode implements AlertWorkflowNode {

    private final Map<String, ToolExecutor> executorsByKind;

    public SilenceNode(List<ToolExecutor> toolExecutors) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executorKind", "alertmanager");
        payload.put("action", "createSilence");
        payload.put("silenceCreated", false);
        payload.put("summary", context.getAttribute("resultSummary"));
        putIfPresent(payload, "approvedBy", context.getAttribute("silenceApprovedBy"));
        putIfPresent(payload, "approvalReason", context.getAttribute("silenceApprovalReason"));
        putIfPresent(payload, "approvalExpiresAt", context.getAttribute("silenceApprovalExpiresAt"));
        putIfPresent(payload, "approvalKey", context.getAttribute("silenceApprovalKey"));

        if (!isSilenceApproved(context)) {
            payload.put("skipped", true);
            payload.put("reason", "silence approval or policy opt-in missing");
            return new NodeResult("silenceNode", NodeStatus.SUCCESS, "Silence not requested", payload);
        }

        ToolExecutor alertmanager = executorsByKind.get("alertmanager");
        if (alertmanager == null) {
            payload.put("reason", "alertmanager executor unavailable");
            return new NodeResult("silenceNode", NodeStatus.FAILURE, "Failed to create approved silence", payload);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceName", context.getAlarmEvent().nodeName());
        params.put("durationMinutes", 30);
        params.put("summary", payload.get("summary"));
        putIfPresent(params, "approvedBy", context.getAttribute("silenceApprovedBy"));
        putIfPresent(params, "approvalReason", context.getAttribute("silenceApprovalReason"));
        putIfPresent(params, "approvalExpiresAt", context.getAttribute("silenceApprovalExpiresAt"));
        Map<String, Object> result = alertmanager.execute("createSilence", params);
        payload.put("result", result);
        payload.put("silenceCreated", !isFailure(result));
        if (isFailure(result)) {
            return new NodeResult("silenceNode", NodeStatus.FAILURE, "Failed to create approved silence", payload);
        }
        return new NodeResult("silenceNode", NodeStatus.SUCCESS, "Created approved silence", payload);
    }

    private boolean isSilenceApproved(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation == null
                || evaluation.matchedPolicy() == null
                || evaluation.matchedPolicy().actions() == null) {
            return false;
        }
        if (!evaluation.matchedPolicy().actions().autoSilence()) {
            return false;
        }
        return Boolean.TRUE.equals(context.getAttribute("silenceApproved"));
    }

    private boolean isFailure(Map<String, Object> result) {
        if (result == null) {
            return true;
        }
        Object status = result.get("status");
        Object httpStatus = result.get("httpStatus");
        if ("failed".equalsIgnoreCase(String.valueOf(status))) {
            return true;
        }
        return httpStatus instanceof Number number && number.intValue() >= 400;
    }

    private void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        payload.put(key, value);
    }
}
