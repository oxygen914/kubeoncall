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
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", context.getAlarmEvent().source());
        summary.put("nodeName", context.getAlarmEvent().nodeName());
        summary.put("degraded", context.isDegraded());
        summary.put("failedNodes", context.getFailedNodes());
        summary.put("steps", context.getNodeResults().stream().map(result -> result.nodeName() + ":" + result.status()).toList());
        summary.put("knowledgeHints", context.getAttribute("knowledgeHints"));

        ToolExecutor alertmanager = executorsByKind.get("alertmanager");
        if (alertmanager == null) {
            return new NodeResult("resultPushNode", NodeStatus.FAILURE, "Alertmanager tool executor is not configured", Map.of("executorKind", "alertmanager"));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("serviceName", context.getAlarmEvent().nodeName());
        parameters.put("durationMinutes", 30);
        parameters.put("summary", summary);

        Map<String, Object> pushResult = alertmanager.execute("createSilence", parameters);
        int httpStatus = readHttpStatus(pushResult);
        if (httpStatus >= 400 || "failed".equalsIgnoreCase(String.valueOf(pushResult.get("status")))) {
            return new NodeResult(
                    "resultPushNode",
                    NodeStatus.FAILURE,
                    "Failed to push workflow result",
                    Map.of("executorKind", "alertmanager", "action", "createSilence", "summary", summary, "result", pushResult)
            );
        }

        summary.put("pushResult", pushResult);
        context.putAttribute("resultSummary", summary);
        return new NodeResult(
                "resultPushNode",
                NodeStatus.SUCCESS,
                context.isDegraded() ? "Pushed degraded workflow result" : "Pushed workflow result",
                summary
        );
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return properties.getIntegrations().getAlertmanager().getTimeoutMillis() > 0 ? 200 : 500;
    }
}
