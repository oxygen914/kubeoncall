package com.kubeoncall.workflow.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.notification.AlarmNotificationMessageFactory;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.notification.application.NotificationPublishResult;
import com.kubeoncall.notification.application.NotificationPublisher;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.tool.http.ToolHttpClient;
import com.kubeoncall.workflow.AlertWorkflowContext;
import com.kubeoncall.workflow.AlertWorkflowNode;

@Component
public class NotificationNode implements AlertWorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(NotificationNode.class);

    private final Map<String, ToolExecutor> executorsByKind;
    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;
    private final NotificationPublisher notificationPublisher;

    public NotificationNode(List<ToolExecutor> toolExecutors) {
        this(toolExecutors, null, null, null);
    }

    public NotificationNode(
            List<ToolExecutor> toolExecutors, ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this(toolExecutors, toolHttpClient, properties, null);
    }

    @Autowired
    public NotificationNode(
            List<ToolExecutor> toolExecutors,
            ToolHttpClient toolHttpClient,
            KubeOnCallProperties properties,
            NotificationPublisher notificationPublisher) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
        this.notificationPublisher = notificationPublisher;
    }

    @Override
    public NodeResult execute(AlertWorkflowContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executorKind", "alertmanager");
        payload.put("action", "sendAlertEvent");
        payload.put("summary", summary(context));
        ToolExecutor alertmanager = executorsByKind.get("alertmanager");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alertName", alertName(context));
        params.put("severity", severity(context));
        params.put("summary", payload.get("summary"));
        String idempotencyKey = idempotencyKey(context);
        params.put("idempotencyKey", idempotencyKey);
        payload.put("idempotencyKey", idempotencyKey);
        try {
            NotificationPublishResult durableResult = null;
            try {
                durableResult = publishDurably(context, idempotencyKey);
            } catch (RuntimeException durableFailure) {
                log.warn(
                        "Durable notification submission failed; using legacy path: errorType={}",
                        durableFailure.getClass().getSimpleName());
                payload.put("durableStatus", "SUBMISSION_FAILED");
                payload.put("durableErrorType", durableFailure.getClass().getSimpleName());
            }
            if (durableResult != null) {
                payload.put("durableStatus", durableResult.status().name());
                payload.put("deliveryIds", durableResult.deliveryIds());
                if (durableResult.status() == NotificationPublishResult.Status.QUEUED
                        || durableResult.status() == NotificationPublishResult.Status.ALREADY_QUEUED) {
                    payload.put("action", "queueNotification");
                    return new NodeResult(
                            "notificationNode",
                            NodeStatus.SUCCESS,
                            "Notification queued for durable delivery",
                            payload);
                }
            }
            if (directWebhookConfigured()) {
                Map<String, Object> result = toolHttpClient.post(
                        properties.getIntegrations().getNotification().getEndpoint(),
                        params,
                        properties.getIntegrations().getNotification().getTimeoutMillis(),
                        Map.of("Idempotency-Key", idempotencyKey),
                        Map.of("targetSystem", "notification-webhook", "tool", "notification.send"));
                payload.put("action", "sendWebhook");
                payload.put("result", result);
                return isFailure(result)
                        ? new NodeResult(
                                "notificationNode", NodeStatus.FAILURE, "Failed to send notification webhook", payload)
                        : new NodeResult("notificationNode", NodeStatus.SUCCESS, "Notification webhook sent", payload);
            }
            if (alertmanager == null) {
                payload.put("skipped", true);
                payload.put("reason", "notification webhook and alertmanager executor are unavailable");
                return new NodeResult("notificationNode", NodeStatus.SUCCESS, "Notification skipped", payload);
            }
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

    private NotificationPublishResult publishDurably(AlertWorkflowContext context, String requestId) {
        if (notificationPublisher == null || context.getNormalizedAlarm() == null) {
            return null;
        }
        return notificationPublisher.publish(
                AlarmNotificationMessageFactory.firing(
                        context.getNormalizedAlarm(), context.getEvaluationResult(), "diagnosis-completed"),
                requestId);
    }

    private boolean directWebhookConfigured() {
        return toolHttpClient != null
                && properties != null
                && properties.getIntegrations().getNotification().getEndpoint() != null
                && !properties.getIntegrations().getNotification().getEndpoint().isBlank();
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

    private String idempotencyKey(AlertWorkflowContext context) {
        NormalizedAlarmEvent event = context.getNormalizedAlarm();
        if (event == null) {
            return "alarm:" + context.getAlarmEvent().alarmId();
        }
        return String.join(
                ":",
                "alarm",
                safe(event.fingerprint()),
                event.status() == null ? "unknown" : event.status().name(),
                event.occurredAt() == null ? "unknown" : event.occurredAt().toString());
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
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
