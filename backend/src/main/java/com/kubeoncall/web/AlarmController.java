package com.kubeoncall.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.recovery.AlarmRecoveryService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.alarm.state.AlarmAcknowledgementStore;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.web.dto.AlarmAcknowledgementRequest;
import com.kubeoncall.web.dto.AlarmAcknowledgementResponse;
import com.kubeoncall.web.dto.AlarmRecoveryConfirmationRequest;
import com.kubeoncall.web.dto.AlarmRecoveryConfirmationResponse;
import com.kubeoncall.web.dto.AlarmRequest;
import com.kubeoncall.web.dto.AlarmSilenceApprovalRequest;
import com.kubeoncall.web.dto.AlarmSilenceApprovalResponse;
import com.kubeoncall.workflow.AlertWorkflowService;

@LegacyApiController
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmNormalizer alarmNormalizer;
    private final AlertWorkflowService alertWorkflowService;
    private final AlarmSilenceApprovalStore silenceApprovalStore;
    private final AlarmAcknowledgementStore acknowledgementStore;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;
    private final ExecutionAuditService executionAuditService;
    private final AlarmRecoveryService alarmRecoveryService;
    private final ActiveAlarmStore activeAlarmStore;

    public AlarmController(
            AlarmNormalizer alarmNormalizer,
            AlertWorkflowService alertWorkflowService,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmAcknowledgementStore acknowledgementStore,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            ExecutionAuditService executionAuditService,
            AlarmRecoveryService alarmRecoveryService) {
        this(
                alarmNormalizer,
                alertWorkflowService,
                silenceApprovalStore,
                acknowledgementStore,
                properties,
                metricsService,
                executionAuditService,
                alarmRecoveryService,
                null);
    }

    @Autowired
    public AlarmController(
            AlarmNormalizer alarmNormalizer,
            AlertWorkflowService alertWorkflowService,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmAcknowledgementStore acknowledgementStore,
            KubeOnCallProperties properties,
            KubeOnCallMetricsService metricsService,
            ExecutionAuditService executionAuditService,
            AlarmRecoveryService alarmRecoveryService,
            ActiveAlarmStore activeAlarmStore) {
        this.alarmNormalizer = alarmNormalizer;
        this.alertWorkflowService = alertWorkflowService;
        this.silenceApprovalStore = silenceApprovalStore;
        this.acknowledgementStore = acknowledgementStore;
        this.properties = properties;
        this.metricsService = metricsService;
        this.executionAuditService = executionAuditService;
        this.alarmRecoveryService = alarmRecoveryService;
        this.activeAlarmStore = activeAlarmStore;
    }

    @PostMapping
    public List<NodeResult> ingest(@RequestBody AlarmRequest request) {
        // Normalize both legacy and standard request shapes into one NormalizedAlarmEvent, then run
        // the governed pipeline (policy engine + fingerprint dedup + workflow).
        NormalizedAlarmEvent event = alarmNormalizer.normalize(request);
        return alertWorkflowService.process(event);
    }

    @PostMapping("/acknowledgements")
    public AlarmAcknowledgementResponse acknowledge(@RequestBody AlarmAcknowledgementRequest request) {
        Instant startedAt = Instant.now();
        if (request == null || isBlank(request.fingerprint())) {
            metricsService.recordAlarmAcknowledgement("invalid");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fingerprint is required");
        }
        if (isBlank(request.acknowledgedBy())) {
            metricsService.recordAlarmAcknowledgement("invalid");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "acknowledgedBy is required");
        }
        long ttlSeconds = request.ttlSeconds() == null || request.ttlSeconds() <= 0
                ? properties.getAlarm().getAcknowledgementTtlSeconds()
                : request.ttlSeconds();
        try {
            AlarmAcknowledgementStore.AlarmAcknowledgement acknowledgement = acknowledgementStore.acknowledge(
                    request.fingerprint(),
                    request.acknowledgedBy(),
                    request.reason(),
                    Duration.ofSeconds(Math.max(60, ttlSeconds)));
            metricsService.recordAlarmAcknowledgement("acknowledged");
            recordMtta(acknowledgement.fingerprint(), acknowledgement.acknowledgedAt());
            executionAuditService.recordAlarmExecution(
                    "alarm-ack-" + acknowledgement.fingerprint(),
                    "ACKNOWLEDGED",
                    true,
                    false,
                    "Alarm acknowledged by " + acknowledgement.acknowledgedBy(),
                    null,
                    List.of("alarm.acknowledge"),
                    startedAt,
                    Map.of(
                            "fingerprint", acknowledgement.fingerprint(),
                            "acknowledgedBy", acknowledgement.acknowledgedBy(),
                            "acknowledgedAt", acknowledgement.acknowledgedAt().toString(),
                            "expiresAt", acknowledgement.expiresAt().toString(),
                            "acknowledgementKey", acknowledgementStore.keyFor(acknowledgement.fingerprint())));
            return new AlarmAcknowledgementResponse(
                    acknowledgement.fingerprint(),
                    true,
                    acknowledgement.acknowledgedBy(),
                    acknowledgement.reason(),
                    acknowledgement.acknowledgedAt(),
                    acknowledgement.expiresAt(),
                    acknowledgementStore.keyFor(acknowledgement.fingerprint()));
        } catch (RuntimeException ex) {
            metricsService.recordAlarmAcknowledgement("failed");
            throw ex;
        }
    }

    private void recordMtta(String fingerprint, Instant acknowledgedAt) {
        if (activeAlarmStore == null || acknowledgedAt == null) {
            return;
        }
        ActiveAlarmState active = activeAlarmStore.find(fingerprint).orElse(null);
        if (active == null || active.firstSeen() == null) {
            return;
        }
        long durationMs =
                Math.max(0, Duration.between(active.firstSeen(), acknowledgedAt).toMillis());
        metricsService.recordAlarmDuration(
                "mtta",
                active.severity() == null ? "unknown" : active.severity().name(),
                durationMs);
    }

    @PostMapping("/recovery-confirmations")
    public AlarmRecoveryConfirmationResponse confirmRecovery(@RequestBody AlarmRecoveryConfirmationRequest request) {
        if (request == null || isBlank(request.fingerprint())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fingerprint is required");
        }
        if (isBlank(request.confirmedBy())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirmedBy is required");
        }
        try {
            AlarmRecoveryState state = alarmRecoveryService.confirmManual(
                    request.fingerprint(),
                    request.confirmedBy(),
                    Boolean.TRUE.equals(request.healthCheckPassed()),
                    request.note());
            return new AlarmRecoveryConfirmationResponse(
                    state.fingerprint(),
                    state.status(),
                    state.severity() == null ? null : state.severity().name(),
                    state.policyId(),
                    state.candidateAt(),
                    state.confirmAfter(),
                    state.manualConfirmationRequired(),
                    state.confirmedBy(),
                    state.healthCheckPassed(),
                    state.confirmedAt());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/silence-approvals")
    public AlarmSilenceApprovalResponse approveSilence(@RequestBody AlarmSilenceApprovalRequest request) {
        if (request == null || isBlank(request.fingerprint())) {
            metricsService.recordAlarmSilenceApproval("invalid");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fingerprint is required");
        }
        if (isBlank(request.approvedBy())) {
            metricsService.recordAlarmSilenceApproval("invalid");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "approvedBy is required");
        }
        long ttlSeconds = request.ttlSeconds() == null || request.ttlSeconds() <= 0
                ? properties.getAlarm().getSilenceApprovalTtlSeconds()
                : request.ttlSeconds();
        try {
            AlarmSilenceApprovalStore.SilenceApproval approval = silenceApprovalStore.approve(
                    request.fingerprint(),
                    request.approvedBy(),
                    request.reason(),
                    Duration.ofSeconds(Math.max(60, ttlSeconds)));
            metricsService.recordAlarmSilenceApproval("approved");
            return new AlarmSilenceApprovalResponse(
                    approval.fingerprint(),
                    true,
                    approval.approvedBy(),
                    approval.reason(),
                    approval.approvedAt(),
                    approval.expiresAt(),
                    silenceApprovalStore.keyFor(approval.fingerprint()));
        } catch (RuntimeException ex) {
            metricsService.recordAlarmSilenceApproval("failed");
            throw ex;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
