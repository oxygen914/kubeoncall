package com.kubeoncall.workflow.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;

/**
 * Emits a structured diagnostic summary as the workflow's terminal result.
 *
 * <p>Notification, ticket creation, and silence are deliberately split into dedicated safe nodes.
 * This node is the source of truth for the diagnostic summary and does not call external systems.
 */
@Component
public class ResultPushNode implements AlertWorkflowNode {

    public ResultPushNode(List<ToolExecutor> toolExecutors, KubeOnCallProperties properties) {}

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> summary = buildSummary(context);
        context.putAttribute("resultSummary", summary);

        return new NodeResult(
                "resultPushNode",
                NodeStatus.SUCCESS,
                context.isDegraded() ? "Recorded degraded diagnostic summary" : "Recorded diagnostic summary",
                summary);
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
        summary.put(
                "steps",
                context.getNodeResults().stream()
                        .map(result -> result.nodeName() + ":" + result.status())
                        .toList());
        summary.put("knowledgeHints", context.getAttribute("knowledgeHints"));
        summary.put("diagnosis", diagnosis(context));
        if (normalized != null) {
            summary.put("fingerprint", normalized.fingerprint());
            summary.put("alertName", normalized.alertName());
            summary.put(
                    "resourceType",
                    normalized.resourceType() == null
                            ? null
                            : normalized.resourceType().name());
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

    private Map<String, Object> diagnosis(AlertWorkflowContext context) {
        Object existing = context.getAttribute("diagnosis");
        if (existing instanceof Map<?, ?> map) {
            Map<String, Object> diagnosis = new LinkedHashMap<>();
            map.forEach((key, value) -> diagnosis.put(String.valueOf(key), value));
            return Map.copyOf(diagnosis);
        }
        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("strategy", "NODE_MVP_EVIDENCE");
        diagnosis.put("state", summarizeToolResult(context.getAttribute("stateCompareResult")));
        diagnosis.put("device", summarizeToolResult(context.getAttribute("deviceInfoResult")));
        diagnosis.put(
                "conclusion",
                context.isDegraded()
                        ? "Diagnosis is degraded because one or more optional evidence sources failed"
                        : "Node evidence was collected; review the metric series and current alert state");
        return Map.copyOf(diagnosis);
    }

    private Map<String, Object> summarizeToolResult(Object raw) {
        if (!(raw instanceof Map<?, ?> result)) {
            return Map.of("available", false);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("available", true);
        copyScalar(result, summary, "status");
        copyScalar(result, summary, "httpStatus");
        copyScalar(result, summary, "latencyMs");
        copyScalar(result, summary, "query");
        Object response = result.get("response");
        if (response instanceof Map<?, ?> responseMap
                && responseMap.get("data") instanceof Map<?, ?> data
                && data.get("result") instanceof List<?> series) {
            summary.put("seriesCount", series.size());
            summary.put(
                    "samples", series.stream().limit(5).map(this::sampleSummary).toList());
        }
        return Map.copyOf(summary);
    }

    private Map<String, Object> sampleSummary(Object raw) {
        if (!(raw instanceof Map<?, ?> sample)) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        if (sample.get("metric") instanceof Map<?, ?> metric) {
            Map<String, Object> labels = new LinkedHashMap<>();
            metric.forEach((key, value) -> labels.put(String.valueOf(key), value));
            summary.put("metric", Map.copyOf(labels));
        }
        if (sample.get("value") != null) {
            summary.put("value", sample.get("value"));
        }
        if (sample.get("values") instanceof List<?> values && !values.isEmpty()) {
            summary.put("latestValue", values.get(values.size() - 1));
        }
        return Map.copyOf(summary);
    }

    private void copyScalar(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    private static String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
    }
}
