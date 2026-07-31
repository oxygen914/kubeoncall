package com.kubeoncall.alarm.recovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.notification.AlarmNotificationMessageFactory;
import com.kubeoncall.notification.application.NotificationPublishResult;
import com.kubeoncall.notification.application.NotificationPublisher;
import com.kubeoncall.tool.ToolExecutor;

@Service
public class AlarmRecoveryFinalizer {

    private static final Logger log = LoggerFactory.getLogger(AlarmRecoveryFinalizer.class);

    private final Map<String, ToolExecutor> executorsByKind;
    private final NotificationPublisher notificationPublisher;

    public AlarmRecoveryFinalizer(List<ToolExecutor> toolExecutors) {
        this(toolExecutors, null);
    }

    @Autowired
    public AlarmRecoveryFinalizer(List<ToolExecutor> toolExecutors, NotificationPublisher notificationPublisher) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.notificationPublisher = notificationPublisher;
    }

    public RecoveryActions finalizeRecovery(AlarmRecoveryState state, String actor, String note) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fingerprint", state.fingerprint());
        parameters.put("alarmId", state.alarmId());
        parameters.put(
                "severity", state.severity() == null ? null : state.severity().name());
        parameters.put("policyId", state.policyId());
        parameters.put("eventType", "RECOVERY_CONFIRMED");
        parameters.put("resolution", "resolved_by_recovery");
        parameters.put("confirmedBy", actor);
        parameters.put("note", note == null ? "" : note);

        Map<String, Object> notification = publishOrLegacy(state, actor, note, parameters);
        Map<String, Object> resolution = execute("incident", "resolveIncident", parameters);
        boolean postmortemRequired = state.severity() == AlarmSeverity.P0 || state.severity() == AlarmSeverity.P1;
        Map<String, Object> postmortem = postmortemRequired
                ? execute("incident", "createPostmortem", parameters)
                : Map.of("status", "not_required");
        boolean success = !isFailure(notification) && !isFailure(resolution) && !isFailure(postmortem);
        return new RecoveryActions(success, postmortemRequired, notification, resolution, postmortem);
    }

    private Map<String, Object> publishOrLegacy(
            AlarmRecoveryState state, String actor, String note, Map<String, Object> legacyParameters) {
        if (notificationPublisher != null) {
            try {
                java.time.Instant occurredAt =
                        state.confirmedAt() == null ? java.time.Instant.now() : state.confirmedAt();
                NotificationPublishResult result = notificationPublisher.publish(
                        AlarmNotificationMessageFactory.recovery(state, actor, note, occurredAt), state.alarmId());
                if (result.status() == NotificationPublishResult.Status.QUEUED
                        || result.status() == NotificationPublishResult.Status.ALREADY_QUEUED) {
                    return Map.of(
                            "status",
                            "success",
                            "deliveryStatus",
                            result.status().name(),
                            "deliveryIds",
                            result.deliveryIds());
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Durable alarm recovery notification failed; using legacy path: errorType={}",
                        exception.getClass().getSimpleName());
            }
        }
        return execute("alertmanager", "sendAlertEvent", legacyParameters);
    }

    private Map<String, Object> execute(String executorKind, String action, Map<String, Object> parameters) {
        ToolExecutor executor = executorsByKind.get(executorKind);
        if (executor == null) {
            return Map.of(
                    "status",
                    "failed",
                    "errorType",
                    "IntegrationUnavailable",
                    "errorMessage",
                    executorKind + " executor unavailable",
                    "executor",
                    executorKind,
                    "action",
                    action);
        }
        try {
            return executor.execute(action, parameters);
        } catch (RuntimeException ex) {
            log.warn(
                    "Alarm recovery finalization failed: executor={}, action={}, errorType={}",
                    executorKind,
                    action,
                    ex.getClass().getSimpleName());
            return Map.of(
                    "status",
                    "failed",
                    "errorType",
                    "IntegrationCallFailed",
                    "errorMessage",
                    "Integration call failed",
                    "executor",
                    executorKind,
                    "action",
                    action);
        }
    }

    private boolean isFailure(Map<String, Object> result) {
        if (result == null) {
            return true;
        }
        Object status = result.get("status");
        Object httpStatus = result.get("httpStatus");
        return "failed".equalsIgnoreCase(String.valueOf(status))
                || httpStatus instanceof Number number && number.intValue() >= 400;
    }

    public record RecoveryActions(
            boolean success,
            boolean postmortemRequired,
            Map<String, Object> notificationResult,
            Map<String, Object> incidentResolutionResult,
            Map<String, Object> postmortemResult) {}
}
