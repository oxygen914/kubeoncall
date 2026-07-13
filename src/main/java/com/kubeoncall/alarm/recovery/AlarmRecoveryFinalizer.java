package com.kubeoncall.alarm.recovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.tool.ToolExecutor;

@Service
public class AlarmRecoveryFinalizer {

    private final Map<String, ToolExecutor> executorsByKind;

    public AlarmRecoveryFinalizer(List<ToolExecutor> toolExecutors) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
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

        Map<String, Object> notification = execute("alertmanager", "sendAlertEvent", parameters);
        Map<String, Object> resolution = execute("incident", "resolveIncident", parameters);
        boolean postmortemRequired = state.severity() == AlarmSeverity.P0 || state.severity() == AlarmSeverity.P1;
        Map<String, Object> postmortem = postmortemRequired
                ? execute("incident", "createPostmortem", parameters)
                : Map.of("status", "not_required");
        boolean success = !isFailure(notification) && !isFailure(resolution) && !isFailure(postmortem);
        return new RecoveryActions(success, postmortemRequired, notification, resolution, postmortem);
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
            return Map.of(
                    "status",
                    "failed",
                    "errorType",
                    ex.getClass().getSimpleName(),
                    "errorMessage",
                    ex.getMessage() == null ? "integration call failed" : ex.getMessage(),
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
