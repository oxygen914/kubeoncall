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
public class LogCollectionNode implements AlertWorkflowNode {

    private final Map<String, ToolExecutor> executorsByKind;
    private final KubeOnCallProperties properties;

    public LogCollectionNode(List<ToolExecutor> toolExecutors, KubeOnCallProperties properties) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.properties = properties;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        ToolExecutor kubernetes = executorsByKind.get("kubernetes");
        if (kubernetes == null) {
            return new NodeResult("logCollectionNode", NodeStatus.FAILURE, "Kubernetes tool executor is not configured", Map.of("executorKind", "kubernetes"));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("namespace", resolveNamespace(context));
        parameters.put("keyword", context.getAlarmEvent().summary());
        parameters.put("lookbackMinutes", 30);
        Map<String, Object> toolResult = kubernetes.execute("queryLogs", parameters);

        int httpStatus = readHttpStatus(toolResult);
        if (httpStatus >= 400 || "failed".equalsIgnoreCase(String.valueOf(toolResult.get("status")))) {
            return new NodeResult(
                    "logCollectionNode",
                    NodeStatus.FAILURE,
                    "Failed to collect logs",
                    Map.of("executorKind", "kubernetes", "action", "queryLogs", "result", toolResult)
            );
        }

        context.putAttribute("logQuery", context.getAlarmEvent().summary());
        context.putAttribute("logResult", toolResult);
        return new NodeResult(
                "logCollectionNode",
                NodeStatus.SUCCESS,
                "Collected logs for alarm",
                Map.of(
                        "alarmId", context.getAlarmEvent().alarmId(),
                        "query", context.getAlarmEvent().summary(),
                        "executorKind", "kubernetes",
                        "action", "queryLogs",
                        "result", toolResult
                )
        );
    }

    private String resolveNamespace(AlertWorkflowContext context) {
        Object namespace = context.getAlarmEvent().metadata() == null ? null : context.getAlarmEvent().metadata().get("namespace");
        return namespace == null || String.valueOf(namespace).isBlank() ? "default" : String.valueOf(namespace);
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getKubernetes().getTimeoutMillis() > 0 ? 200 : 500;
    }
}
