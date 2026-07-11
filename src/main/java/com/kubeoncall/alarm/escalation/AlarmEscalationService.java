package com.kubeoncall.alarm.escalation;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AlarmEscalationService {

    private final Map<String, ToolExecutor> executorsByKind;

    public AlarmEscalationService(List<ToolExecutor> toolExecutors) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    public NodeResult escalate(NormalizedAlarmEvent event,
                               AlarmEvaluationResult evaluation,
                               long count,
                               long threshold) {
        AlarmSeverity severity = evaluation == null ? event.severity() : evaluation.finalSeverity();
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("fingerprint", event.fingerprint());
        parameters.put("alarmId", event.alarmId());
        parameters.put("alertName", event.alertName());
        parameters.put("severity", severity == null ? null : severity.name());
        parameters.put("count", count);
        parameters.put("threshold", threshold);
        parameters.put("eventType", "UNACKNOWLEDGED_ESCALATION");
        parameters.put("summary", "Unacknowledged alarm exceeded escalation threshold");
        if (evaluation != null && evaluation.matchedPolicy() != null) {
            parameters.put("owner", evaluation.matchedPolicy().owner());
            parameters.put("runbookId", evaluation.matchedPolicy().runbookId());
            parameters.put("notificationChannel", evaluation.matchedPolicy().actions() == null
                    ? null
                    : evaluation.matchedPolicy().actions().notificationChannel());
        }

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(parameters);
        payload.put("tools", List.of("alertmanager.sendAlertEvent", "incident.escalateIncident"));
        Map<String, Object> notificationResult = execute("alertmanager", "sendAlertEvent", parameters);
        Map<String, Object> incidentResult = execute("incident", "escalateIncident", parameters);
        payload.put("notificationResult", notificationResult);
        payload.put("incidentResult", incidentResult);
        boolean success = !isFailure(notificationResult) && !isFailure(incidentResult);
        payload.put("deliveryStatus", success ? "delivered" : "failed");
        return new NodeResult(
                "alarmEscalation",
                success ? NodeStatus.SUCCESS : NodeStatus.FAILURE,
                success ? "Unacknowledged alarm notification and incident escalation delivered"
                        : "Alarm escalation delivery failed",
                payload
        );
    }

    private Map<String, Object> execute(String executorKind,
                                        String action,
                                        Map<String, Object> parameters) {
        ToolExecutor executor = executorsByKind.get(executorKind);
        if (executor == null) {
            return Map.of(
                    "status", "failed",
                    "errorType", "IntegrationUnavailable",
                    "errorMessage", executorKind + " executor unavailable",
                    "executor", executorKind,
                    "action", action
            );
        }
        try {
            return executor.execute(action, parameters);
        } catch (RuntimeException ex) {
            return Map.of(
                    "status", "failed",
                    "errorType", ex.getClass().getSimpleName(),
                    "errorMessage", ex.getMessage() == null ? "integration call failed" : ex.getMessage(),
                    "executor", executorKind,
                    "action", action
            );
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
}
