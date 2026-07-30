package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.notification.AlarmNotificationMessageFactory;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.notification.application.NotificationPublisher;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Coordinates the public alarm workflow entry points without owning individual workflow branches. */
@Service
public class AlertWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AlertWorkflowService.class);

    private final AlarmEventPreparationService eventPreparationService;
    private final AlertWorkflowPreflight workflowPreflight;
    private final AlertWorkflowCoordinator workflowCoordinator;
    private final KubeOnCallMetricsService metricsService;
    private final NotificationPublisher notificationPublisher;

    public AlertWorkflowService(
            AlarmEventPreparationService eventPreparationService,
            AlertWorkflowPreflight workflowPreflight,
            AlertWorkflowCoordinator workflowCoordinator) {
        this(eventPreparationService, workflowPreflight, workflowCoordinator, null, null);
    }

    public AlertWorkflowService(
            AlarmEventPreparationService eventPreparationService,
            AlertWorkflowPreflight workflowPreflight,
            AlertWorkflowCoordinator workflowCoordinator,
            KubeOnCallMetricsService metricsService) {
        this(eventPreparationService, workflowPreflight, workflowCoordinator, metricsService, null);
    }

    @Autowired
    public AlertWorkflowService(
            AlarmEventPreparationService eventPreparationService,
            AlertWorkflowPreflight workflowPreflight,
            AlertWorkflowCoordinator workflowCoordinator,
            KubeOnCallMetricsService metricsService,
            NotificationPublisher notificationPublisher) {
        this.eventPreparationService = eventPreparationService;
        this.workflowPreflight = workflowPreflight;
        this.workflowCoordinator = workflowCoordinator;
        this.metricsService = metricsService;
        this.notificationPublisher = notificationPublisher;
    }

    /**
     * Legacy entry point retained for backwards compatibility. Delegates to
     * {@link #process(NormalizedAlarmEvent)} with a minimal normalized event synthesized from the
     * legacy {@link AlarmEvent}, so the policy and deduplication path remains uniform.
     */
    public List<NodeResult> process(AlarmEvent alarmEvent) {
        return process(toNormalized(alarmEvent));
    }

    /** Processes an already normalized alarm through preflight and the configured workflow. */
    public List<NodeResult> process(NormalizedAlarmEvent event) {
        Instant startedAt = Instant.now();
        AlarmEventPreparationService.PreparedAlarm preparedAlarm = eventPreparationService.prepare(event);
        AlertWorkflowPreflight.Result preflightResult = workflowPreflight.process(preparedAlarm, startedAt);
        if (preflightResult.hasTerminalResult()) {
            return preflightResult.terminalResults();
        }
        if (metricsService != null) {
            metricsService.recordAlarmQuality("actionable");
        }
        publishInitialNotification(preflightResult.preparedAlarm());
        return workflowCoordinator.run(preflightResult, startedAt);
    }

    private void publishInitialNotification(AlarmEventPreparationService.PreparedAlarm preparedAlarm) {
        if (notificationPublisher == null) {
            return;
        }
        try {
            notificationPublisher.publish(
                    AlarmNotificationMessageFactory.firing(
                            preparedAlarm.event(), preparedAlarm.evaluation(), "initial"),
                    preparedAlarm.event().alarmId());
        } catch (RuntimeException exception) {
            log.warn(
                    "Initial durable notification submission failed; diagnosis will continue: errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static NormalizedAlarmEvent toNormalized(AlarmEvent alarmEvent) {
        String fingerprint = alarmEvent.dedupKey();
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = alarmEvent.alarmId() == null ? "legacy-" + alarmEvent.hashCode() : alarmEvent.alarmId();
        }
        return new NormalizedAlarmEvent(
                alarmEvent.alarmId(),
                fingerprint,
                null,
                alarmEvent.source(),
                alarmEvent.severity(),
                null,
                null,
                alarmEvent.nodeName(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                alarmEvent.occurredAt(),
                alarmEvent.summary(),
                alarmEvent.metadata() == null ? Map.of() : alarmEvent.metadata());
    }
}
