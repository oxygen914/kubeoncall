package com.kubeoncall.workflow;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;

@Service
public class AlarmEventPreparationService {

    private final AlarmFingerprintService fingerprintService;
    private final AlarmPolicyEngine policyEngine;
    private final ActiveAlarmStore activeAlarmStore;

    public AlarmEventPreparationService(
            AlarmFingerprintService fingerprintService,
            AlarmPolicyEngine policyEngine,
            ActiveAlarmStore activeAlarmStore) {
        this.fingerprintService = fingerprintService;
        this.policyEngine = policyEngine;
        this.activeAlarmStore = activeAlarmStore;
    }

    public PreparedAlarm prepare(NormalizedAlarmEvent event) {
        String fingerprint = fingerprintService.fingerprint(event);
        NormalizedAlarmEvent preparedEvent = withFingerprint(event, fingerprint);
        AlarmEvaluationResult evaluation = policyEngine.evaluate(preparedEvent);
        ActiveAlarmState activeState = activeAlarmStore.record(preparedEvent, evaluation, fingerprint);
        return new PreparedAlarm(preparedEvent, fingerprint, evaluation, activeState);
    }

    public Optional<AlarmPolicy> findPolicyById(String policyId) {
        return policyEngine.findPolicyById(policyId);
    }

    private NormalizedAlarmEvent withFingerprint(NormalizedAlarmEvent event, String fingerprint) {
        return new NormalizedAlarmEvent(
                event.alarmId(),
                fingerprint,
                event.alertName(),
                event.source(),
                event.rawSeverity(),
                event.severity(),
                event.resourceType(),
                event.resourceName(),
                event.cluster(),
                event.namespace(),
                event.service(),
                event.metricName(),
                event.currentValue(),
                event.threshold(),
                event.unit(),
                event.duration(),
                event.labels(),
                event.annotations(),
                event.runbookId(),
                event.status(),
                event.occurredAt(),
                event.summary(),
                event.metadata());
    }

    public record PreparedAlarm(
            NormalizedAlarmEvent event,
            String fingerprint,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState activeState) {}
}
