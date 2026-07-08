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

@Component
public class StateCompareNode implements AlertWorkflowNode {

    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;

    public StateCompareNode(List<ToolExecutor> toolExecutors, KubeOnCallProperties properties) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        ToolExecutor prometheus = executorsByKind.get("prometheus");
        if (prometheus == null) {
            return new NodeResult("stateCompareNode", NodeStatus.FAILURE, "Prometheus tool executor is not configured", Map.of("executorKind", "prometheus"));
        }

        QueryPlan plan = resolveQueryPlan(context);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", plan.query);
        parameters.put("windowMinutes", plan.windowMinutes);

        Map<String, Object> toolResult = prometheus.execute("rangeQuery", parameters);
        int httpStatus = readHttpStatus(toolResult);
        if (httpStatus >= 400 || "failed".equalsIgnoreCase(String.valueOf(toolResult.get("status")))) {
            return new NodeResult(
                    "stateCompareNode",
                    NodeStatus.FAILURE,
                    "Failed to compare runtime state",
                    Map.of("executorKind", "prometheus", "action", "rangeQuery", "result", toolResult)
            );
        }

        context.putAttribute("baseline", plan.baseline);
        context.putAttribute("stateCompareResult", toolResult);
        String severity = severityFor(context);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("severity", severity);
        payload.put("baseline", plan.baseline);
        payload.put("query", plan.query);
        payload.put("windowMinutes", plan.windowMinutes);
        payload.put("querySource", plan.source);
        payload.put("executorKind", "prometheus");
        payload.put("action", "rangeQuery");
        payload.put("result", toolResult);
        return new NodeResult(
                "stateCompareNode",
                NodeStatus.SUCCESS,
                "Compared runtime state against baseline",
                payload
        );
    }

    /**
     * Resolve the PromQL and window for this run. Preference order:
     * <ol>
     *   <li>Policy PromQL/window (from the matched alarm policy) — the governed default.</li>
     *   <li>Explicit {@code compareQuery} in the alarm metadata — legacy override.</li>
     *   <li>Fallback {@code up{instance="nodeName"}} — last resort, never silently empty.</li>
     * </ol>
     */
    private QueryPlan resolveQueryPlan(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.promql() != null && !evaluation.promql().isBlank()) {
            int windowMinutes = parseWindowMinutes(evaluation.window(), 15);
            return new QueryPlan(evaluation.promql().trim(), windowMinutes, evaluation.runbookId() == null ? "policy" : evaluation.runbookId(), "policy");
        }
        NormalizedAlarmEvent normalized = context.getNormalizedAlarm();
        if (normalized != null && normalized.metadata() != null) {
            Object query = normalized.metadata().get("compareQuery");
            if (query != null && !String.valueOf(query).isBlank()) {
                return new QueryPlan(String.valueOf(query), 15, String.valueOf(normalized.metadata().getOrDefault("baseline", "lab-default")), "metadata");
            }
        }
        // Legacy path: metadata on the AlarmEvent.
        Map<String, Object> metadata = context.getAlarmEvent().metadata() == null ? Map.of() : context.getAlarmEvent().metadata();
        Object query = metadata.get("compareQuery");
        String baseline = String.valueOf(metadata.getOrDefault("baseline", "lab-default"));
        if (query != null && !String.valueOf(query).isBlank()) {
            return new QueryPlan(String.valueOf(query), 15, baseline, "metadata");
        }
        return new QueryPlan("up{instance=\"" + context.getAlarmEvent().nodeName() + "\"}", 15, baseline, "fallback");
    }

    private static int parseWindowMinutes(String window, int fallback) {
        if (window == null || window.isBlank()) {
            return fallback;
        }
        String trimmed = window.trim().toLowerCase();
        try {
            if (trimmed.endsWith("m")) {
                return Integer.parseInt(trimmed.substring(0, trimmed.length() - 1).trim());
            }
            if (trimmed.endsWith("h")) {
                return Math.max(1, Integer.parseInt(trimmed.substring(0, trimmed.length() - 1).trim()) * 60);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String severityFor(AlertWorkflowContext context) {
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
        return properties.getIntegrations().getPrometheus().getTimeoutMillis() > 0 ? 200 : 500;
    }

    private record QueryPlan(String query, int windowMinutes, String baseline, String source) {
    }
}
