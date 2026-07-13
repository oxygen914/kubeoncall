package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;

/** Coordinates the public alarm workflow entry points without owning individual workflow branches. */
@Service
public class AlertWorkflowService {

    private final AlarmEventPreparationService eventPreparationService;
    private final AlertWorkflowPreflight workflowPreflight;
    private final AlertWorkflowCoordinator workflowCoordinator;

    public AlertWorkflowService(
            AlarmEventPreparationService eventPreparationService,
            AlertWorkflowPreflight workflowPreflight,
            AlertWorkflowCoordinator workflowCoordinator) {
        this.eventPreparationService = eventPreparationService;
        this.workflowPreflight = workflowPreflight;
        this.workflowCoordinator = workflowCoordinator;
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
        return workflowCoordinator.run(preflightResult, startedAt);
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
