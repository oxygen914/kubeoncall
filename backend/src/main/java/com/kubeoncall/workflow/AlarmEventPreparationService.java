package com.kubeoncall.workflow;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Service
public class AlarmEventPreparationService {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventPreparationService.class);

    private final AlarmFingerprintService fingerprintService;
    private final AlarmPolicyEngine policyEngine;
    private final ActiveAlarmStore activeAlarmStore;
    private final AlarmIncidentProjection incidentProjection;
    private final KubeOnCallProperties properties;

    public AlarmEventPreparationService(
            AlarmFingerprintService fingerprintService,
            AlarmPolicyEngine policyEngine,
            ActiveAlarmStore activeAlarmStore,
            AlarmIncidentProjection incidentProjection) {
        this(fingerprintService, policyEngine, activeAlarmStore, incidentProjection, new KubeOnCallProperties());
    }

    @Autowired
    public AlarmEventPreparationService(
            AlarmFingerprintService fingerprintService,
            AlarmPolicyEngine policyEngine,
            ActiveAlarmStore activeAlarmStore,
            AlarmIncidentProjection incidentProjection,
            KubeOnCallProperties properties) {
        this.fingerprintService = fingerprintService;
        this.policyEngine = policyEngine;
        this.activeAlarmStore = activeAlarmStore;
        this.incidentProjection = incidentProjection;
        this.properties = properties;
    }

    public PreparedAlarm prepare(NormalizedAlarmEvent event) {
        String fingerprint = fingerprintService.fingerprint(event);
        NormalizedAlarmEvent preparedEvent = withFingerprint(event, fingerprint);
        AlarmEvaluationResult evaluation = policyEngine.evaluate(preparedEvent);
        ActiveAlarmState activeState;
        if (properties.getDataMigration().getAlarmWriteMode()
                == com.kubeoncall.common.config.DataMigrationProperties.AlarmWriteMode.MYSQL_PRIMARY) {
            // MySQL success is mandatory here. Redis is populated only after the fact write and is
            // intentionally non-blocking compatibility state for existing workflow consumers.
            ActiveAlarmState primaryState = incidentProjection.projectPrimary(preparedEvent, evaluation);
            try {
                activeState = activeAlarmStore.record(preparedEvent, evaluation, fingerprint);
            } catch (RuntimeException ex) {
                log.warn(
                        "MySQL-primary alarm written but Redis compatibility projection failed: fingerprint={}",
                        fingerprint);
                activeState = primaryState;
            }
        } else {
            activeState = activeAlarmStore.record(preparedEvent, evaluation, fingerprint);
            // REDIS_PRIMARY and DUAL_WRITE retain Redis as the workflow state source while MySQL
            // receives the established idempotent projection.
            incidentProjection.project(preparedEvent, evaluation, activeState);
        }
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
