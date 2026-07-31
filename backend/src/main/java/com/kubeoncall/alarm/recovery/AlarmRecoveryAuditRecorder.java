package com.kubeoncall.alarm.recovery;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.service.ExecutionAuditService;

@Component
public class AlarmRecoveryAuditRecorder {

    private final ExecutionAuditService executionAuditService;

    public AlarmRecoveryAuditRecorder(ExecutionAuditService executionAuditService) {
        this.executionAuditService = executionAuditService;
    }

    public void recordConfirmation(
            AlarmRecoveryState state,
            String actor,
            boolean healthCheckPassed,
            Instant confirmedAt,
            AlarmRecoveryHealthChecker.HealthCheckResult healthResult,
            AlarmRecoveryFinalizer.RecoveryActions recoveryActions) {
        String auditStatus = recoveryActions.success() ? "RECOVERED" : "RECOVERED_DEGRADED";
        String failureReason = recoveryActions.success() ? null : "Recovery confirmed but external finalization failed";
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fingerprint", state.fingerprint());
        metadata.put("severity", severityName(state.severity()));
        metadata.put("policyId", state.policyId() == null ? "" : state.policyId());
        metadata.put("candidateAt", state.candidateAt().toString());
        metadata.put("confirmAfter", state.confirmAfter().toString());
        metadata.put("confirmedAt", confirmedAt.toString());
        metadata.put("confirmedBy", actor);
        metadata.put("healthCheckPassed", healthCheckPassed);
        metadata.put("healthCheckStatus", healthResult.status());
        metadata.put("healthCheckDetails", healthResult.details());
        metadata.put("manualConfirmation", state.manualConfirmationRequired());
        metadata.put("recoveryFinalizationSucceeded", recoveryActions.success());
        metadata.put("postmortemRequired", recoveryActions.postmortemRequired());
        metadata.put("notificationResult", recoveryActions.notificationResult());
        metadata.put("incidentResolutionResult", recoveryActions.incidentResolutionResult());
        metadata.put("postmortemResult", recoveryActions.postmortemResult());
        executionAuditService.recordAlarmExecution(
                "alarm-recovery-" + state.fingerprint() + "-" + confirmedAt.toEpochMilli(),
                auditStatus,
                true,
                state.manualConfirmationRequired(),
                "Alarm recovery confirmed by " + actor,
                failureReason,
                List.of(
                        "alarm.recovery.confirm",
                        "alertmanager.sendAlertEvent",
                        "incident.resolveIncident",
                        recoveryActions.postmortemRequired()
                                ? "incident.createPostmortem"
                                : "incident.postmortem.not_required"),
                state.candidateAt(),
                metadata);
    }

    public void recordTimeout(AlarmRecoveryState state, Instant timedOutAt) {
        executionAuditService.recordAlarmExecution(
                "alarm-recovery-timeout-" + state.fingerprint() + "-" + timedOutAt.toEpochMilli(),
                "RECOVERY_CONFIRMATION_TIMEOUT",
                false,
                state.manualConfirmationRequired(),
                "Recovery candidate timed out before confirmation",
                "RECOVERY_CONFIRMATION_TIMEOUT",
                List.of("alarm.recovery.timeout"),
                state.candidateAt(),
                Map.of(
                        "fingerprint", state.fingerprint(),
                        "severity", severityName(state.severity()),
                        "policyId", state.policyId() == null ? "" : state.policyId(),
                        "confirmAfter", state.confirmAfter().toString(),
                        "timedOutAt", timedOutAt.toString(),
                        "manualConfirmation", state.manualConfirmationRequired()));
    }

    private String severityName(AlarmSeverity severity) {
        return severity == null ? "UNKNOWN" : severity.name();
    }
}
