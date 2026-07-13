package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.recovery.AlarmRecoveryService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

/** Handles recovery-specific early completion before the normal alarm workflow runs. */
@Service
public class AlarmWorkflowRecoveryHandler {

    private final AlarmEventPreparationService eventPreparationService;
    private final AlarmRecoveryService alarmRecoveryService;
    private final AlarmWorkflowAuditRecorder auditRecorder;

    public AlarmWorkflowRecoveryHandler(
            AlarmEventPreparationService eventPreparationService,
            AlarmRecoveryService alarmRecoveryService,
            AlarmWorkflowAuditRecorder auditRecorder) {
        this.eventPreparationService = eventPreparationService;
        this.alarmRecoveryService = alarmRecoveryService;
        this.auditRecorder = auditRecorder;
    }

    public void cancelPendingRecovery(com.kubeoncall.alarm.domain.NormalizedAlarmEvent event) {
        if (event.status() != AlarmStatus.RESOLVED) {
            alarmRecoveryService.cancelIfPending(event.fingerprint());
        }
    }

    public Optional<List<NodeResult>> handle(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            Instant startedAt) {
        if (preparedAlarm.event().status() != AlarmStatus.RESOLVED) {
            return Optional.empty();
        }

        AlarmPolicy recoveryPolicy = preparedAlarm.evaluation().matchedPolicy();
        if (recoveryPolicy == null && preparedAlarm.activeState() != null) {
            recoveryPolicy = eventPreparationService
                    .findPolicyById(preparedAlarm.activeState().policyId())
                    .orElse(null);
        }
        AlarmRecoveryState recoveryState =
                alarmRecoveryService.begin(preparedAlarm.event(), recoveryPolicy, preparedAlarm.activeState());
        LinkedHashMap<String, Object> payload = auditRecorder.recoveryPayload(recoveryState);
        payload.put("activeAlarm", auditRecorder.activeAlarmPayload(preparedAlarm.activeState()));
        NodeResult result = new NodeResult(
                "alarmRecoveryPending",
                NodeStatus.SUCCESS,
                recoveryState.manualConfirmationRequired()
                        ? "Alarm recovery is stable-window pending and requires manual confirmation"
                        : "Alarm recovery is pending the configured stability window",
                payload);
        auditRecorder.recordTerminal(
                preparedAlarm,
                memoryRecall,
                result,
                "RECOVERY_PENDING",
                false,
                recoveryState.manualConfirmationRequired(),
                result.message(),
                List.of("alarm.recovery.candidate"),
                startedAt,
                Map.of(
                        "recoveryStatus", recoveryState.status(),
                        "recoveryConfirmAfter", recoveryState.confirmAfter().toString(),
                        "manualRecoveryConfirmation", recoveryState.manualConfirmationRequired()));
        return Optional.of(List.of(result));
    }
}
