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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emits a structured diagnostic summary as the workflow's terminal result.
 *
 * <p>This node previously called {@code alertmanager.createSilence} by default. Silence is a
 * change/maintenance action and must never be the default outcome of an alarm — especially a P0/P1.
 * Silence is now only created when the matched policy explicitly opts in via
 * {@code actions.autoSilence=true} AND the run is in an approved/maintenance context (signalled by
 * {@code silenceApproved=true} on the context). In every other case the node just records the
 * diagnostic summary and (best-effort) sends a non-mutating alert event.
 */
@Component
public class ResultPushNode implements AlertWorkflowNode {

    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;

    public ResultPushNode(List<ToolExecutor> toolExecutors, KubeOnCallProperties properties) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> summary = buildSummary(context);
        context.putAttribute("resultSummary", summary);

        boolean silenceApproved = isSilenceApproved(context);
        if (silenceApproved) {
            NodeResult silenceResult = createSilence(context, summary);
            if (silenceResult != null) {
                summary.put("pushResult", silenceResult.payload());
                return silenceResult;
            }
        }

        // Best-effort non-mutating alert event so Alertmanager stays in sync without a silence.
        ToolExecutor alertmanager = executorsByKind.get("alertmanager");
        if (alertmanager != null) {
            try {
                Map<String, Object> eventParams = new LinkedHashMap<>();
                eventParams.put("alertName", alertName(context));
                eventParams.put("severity", severity(context));
                eventParams.put("summary", summary);
                Map<String, Object> eventResult = alertmanager.execute("sendAlertEvent", eventParams);
                int httpStatus = readHttpStatus(eventResult);
                if (httpStatus < 400 && !"failed".equalsIgnoreCase(String.valueOf(eventResult.get("status")))) {
                    summary.put("alertEvent", eventResult);
                }
            } catch (RuntimeException ignored) {
                // Notification is best-effort; the diagnostic summary is the source of truth.
            }
        }

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

    /** Silence is only allowed when the matched policy opts in AND an approval flag is set on the context. */
    private boolean isSilenceApproved(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation == null || evaluation.matchedPolicy() == null || evaluation.matchedPolicy().actions() == null) {
            return false;
        }
        if (!evaluation.matchedPolicy().actions().autoSilence()) {
            return false;
        }
        Object approved = context.getAttribute("silenceApproved");
        return Boolean.TRUE.equals(approved);
    }

    private NodeResult createSilence(AlertWorkflowContext context, Map<String, Object> summary) {
        ToolExecutor alertmanager = executorsByKind.get("alertmanager");
        if (alertmanager == null) {
            return null;
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("serviceName", context.getAlarmEvent().nodeName());
        parameters.put("durationMinutes", 30);
        parameters.put("summary", summary);
        Map<String, Object> pushResult = alertmanager.execute("createSilence", parameters);
        int httpStatus = readHttpStatus(pushResult);
        summary.put("silenceCreated", httpStatus < 400);
        if (httpStatus >= 400 || "failed".equalsIgnoreCase(String.valueOf(pushResult.get("status")))) {
            return new NodeResult(
                    "resultPushNode",
                    NodeStatus.FAILURE,
                    "Failed to create approved silence",
                    Map.of("executorKind", "alertmanager", "action", "createSilence", "summary", summary, "result", pushResult)
            );
        }
        summary.put("pushResult", pushResult);
        return new NodeResult(
                "resultPushNode",
                NodeStatus.SUCCESS,
                "Created approved silence and recorded diagnostic summary",
                summary
        );
    }

    private static String alertName(AlertWorkflowContext context) {
        NormalizedAlarmEvent normalized = context.getNormalizedAlarm();
        return normalized != null && normalized.alertName() != null ? normalized.alertName() : context.getAlarmEvent().summary();
    }

    private static String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getAlertmanager().getTimeoutMillis() > 0 ? 200 : 500;
    }
}
