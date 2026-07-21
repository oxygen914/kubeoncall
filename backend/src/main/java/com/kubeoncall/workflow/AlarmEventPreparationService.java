package com.kubeoncall.workflow;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;

@Service
public class AlarmEventPreparationService {

    private final AlarmFingerprintService fingerprintService;
    private final AlarmPolicyEngine policyEngine;
    private final ActiveAlarmStore activeAlarmStore;
    private final AlarmIncidentProjection incidentProjection;

    public AlarmEventPreparationService(
            AlarmFingerprintService fingerprintService,
            AlarmPolicyEngine policyEngine,
            ActiveAlarmStore activeAlarmStore,
            AlarmIncidentProjection incidentProjection) {
        this.fingerprintService = fingerprintService;
        this.policyEngine = policyEngine;
        this.activeAlarmStore = activeAlarmStore;
        this.incidentProjection = incidentProjection;
    }

    public PreparedAlarm prepare(NormalizedAlarmEvent event) {
        String fingerprint = fingerprintService.fingerprint(event);
        NormalizedAlarmEvent preparedEvent = withFingerprint(event, fingerprint);
        AlarmEvaluationResult evaluation = policyEngine.evaluate(preparedEvent);
        ActiveAlarmState activeState = activeAlarmStore.record(preparedEvent, evaluation, fingerprint);
        // Shadow-write the MySQL read model. Redis remains authoritative; the projection is
        // best-effort and swallows failures so a MySQL outage never blocks alarm processing.
        incidentProjection.project(preparedEvent, evaluation, activeState);
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
