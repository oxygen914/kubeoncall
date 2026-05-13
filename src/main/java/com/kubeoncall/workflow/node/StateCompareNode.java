package com.kubeoncall.workflow.node;

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

        Map<String, Object> metadata = context.getAlarmEvent().metadata() == null ? Map.of() : context.getAlarmEvent().metadata();
        String baseline = String.valueOf(metadata.getOrDefault("baseline", "lab-default"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", resolveQuery(context));
        parameters.put("windowMinutes", 15);

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

        context.putAttribute("baseline", baseline);
        context.putAttribute("stateCompareResult", toolResult);
        return new NodeResult(
                "stateCompareNode",
                NodeStatus.SUCCESS,
                "Compared runtime state against baseline",
                Map.of(
                        "severity", context.getAlarmEvent().severity(),
                        "baseline", baseline,
                        "executorKind", "prometheus",
                        "action", "rangeQuery",
                        "result", toolResult
                )
        );
    }

    private String resolveQuery(AlertWorkflowContext context) {
        Map<String, Object> metadata = context.getAlarmEvent().metadata() == null ? Map.of() : context.getAlarmEvent().metadata();
        Object query = metadata.get("compareQuery");
        if (query != null && !String.valueOf(query).isBlank()) {
            return String.valueOf(query);
        }
        return "up{instance=\"" + context.getAlarmEvent().nodeName() + "\"}";
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getPrometheus().getTimeoutMillis() > 0 ? 200 : 500;
    }
}
