package com.kubeoncall.workflow.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;

@Component
public class NotificationNode implements AlertWorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(NotificationNode.class);

    private final Map<String, ToolExecutor> executorsByKind;

    public NotificationNode(List<ToolExecutor> toolExecutors) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executorKind", "alertmanager");
        payload.put("action", "sendAlertEvent");
        payload.put("summary", summary(context));
        ToolExecutor alertmanager = executorsByKind.get("alertmanager");
        if (alertmanager == null) {
            payload.put("skipped", true);
            payload.put("reason", "alertmanager executor unavailable");
            return new NodeResult("notificationNode", NodeStatus.SUCCESS, "Notification skipped", payload);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alertName", alertName(context));
        params.put("severity", severity(context));
        params.put("summary", payload.get("summary"));
        try {
            Map<String, Object> result = alertmanager.execute("sendAlertEvent", params);
            payload.put("result", result);
            if (isFailure(result)) {
                return new NodeResult("notificationNode", NodeStatus.FAILURE, "Failed to send alert event", payload);
            }
            return new NodeResult("notificationNode", NodeStatus.SUCCESS, "Alert event sent", payload);
        } catch (RuntimeException ex) {
            log.warn("Alert notification failed: errorType={}", ex.getClass().getSimpleName());
            payload.put("exceptionType", ex.getClass().getSimpleName());
            return new NodeResult("notificationNode", NodeStatus.FAILURE, "Failed to send alert event", payload);
        }
    }

    private Map<String, Object> summary(AlertWorkflowContext context) {
        Object value = context.getAttribute("resultSummary");
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, val) -> converted.put(String.valueOf(key), val));
            return converted;
        }
        return Map.of("degraded", context.isDegraded(), "failedNodes", context.getFailedNodes());
    }

    private String alertName(AlertWorkflowContext context) {
        NormalizedAlarmEvent normalized = context.getNormalizedAlarm();
        return normalized != null && normalized.alertName() != null
                ? normalized.alertName()
                : context.getAlarmEvent().summary();
    }

    private String severity(AlertWorkflowContext context) {
        AlarmEvaluationResult evaluation = context.getEvaluationResult();
        if (evaluation != null && evaluation.finalSeverity() != null) {
            return evaluation.finalSeverity().name();
        }
        return context.getAlarmEvent().severity();
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
}
