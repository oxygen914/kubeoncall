package com.kubeoncall.workflow.node;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a structured diagnostic summary as the workflow's terminal result.
 *
 * <p>Notification, ticket creation, and silence are deliberately split into dedicated safe nodes.
 * This node is the source of truth for the diagnostic summary and does not call external systems.
 */
@Component
public class ResultPushNode implements AlertWorkflowNode {

    public ResultPushNode(List<ToolExecutor> toolExecutors, KubeOnCallProperties properties) {
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> summary = buildSummary(context);
        context.putAttribute("resultSummary", summary);

        return new NodeResult(
                "resultPushNode",
                NodeStatus.SUCCESS,
                context.isDegraded() ? "Recorded degraded diagnostic summary" : "Recorded diagnostic summary",
                summary
        );
    }

    private Map<String, Object> buildSummary(AlertWorkflowContext context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        NormalizedAlarmEvent normalized = context.getNormalizedAlarm();
        summary.put("source", context.getAlarmEvent().source());
        summary.put("nodeName", context.getAlarmEvent().nodeName());
        summary.put("degraded", context.isDegraded());
        summary.put("severity", severity(context));
        summary.put("failedNodes", context.getFailedNodes());
        summary.put("skippedNodes", context.getSkippedNodes());
        summary.put("steps", context.getNodeResults().stream().map(result -> result.nodeName() + ":" + result.status()).toList());
        summary.put("knowledgeHints", context.getAttribute("knowledgeHints"));
        if (normalized != null) {
            summary.put("fingerprint", normalized.fingerprint());
            summary.put("alertName", normalized.alertName());
            summary.put("resourceType", normalized.resourceType() == null ? null : normalized.resourceType().name());
            summary.put("resourceName", normalized.resourceName());
            summary.put("runbookId", normalized.runbookId());
        }
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null) {
            summary.put("policyId", evaluation.policyId());
            summary.put("matched", evaluation.matched());
            summary.put("reason", evaluation.reason());
            summary.put("silenceCreated", false);
        }
        return summary;
    }

    private static String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
    }
}
