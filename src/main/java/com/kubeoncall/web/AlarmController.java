package com.kubeoncall.web;

import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.workflow.AlertWorkflowService;
import com.kubeoncall.web.dto.AlarmRequest;
import com.kubeoncall.web.dto.AlarmSilenceApprovalRequest;
import com.kubeoncall.web.dto.AlarmSilenceApprovalResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmNormalizer alarmNormalizer;
    private final AlertWorkflowService alertWorkflowService;
    private final AlarmSilenceApprovalStore silenceApprovalStore;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;

    public AlarmController(AlarmNormalizer alarmNormalizer,
                           AlertWorkflowService alertWorkflowService,
                           AlarmSilenceApprovalStore silenceApprovalStore,
                           KubeOnCallProperties properties,
                           KubeOnCallMetricsService metricsService) {
        this.alarmNormalizer = alarmNormalizer;
        this.alertWorkflowService = alertWorkflowService;
        this.silenceApprovalStore = silenceApprovalStore;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    @PostMapping
    public List<NodeResult> ingest(@RequestBody AlarmRequest request) {
        // Normalize both legacy and standard request shapes into one NormalizedAlarmEvent, then run
        // the governed pipeline (policy engine + fingerprint dedup + workflow).
        NormalizedAlarmEvent event = alarmNormalizer.normalize(request);
        return alertWorkflowService.process(event);
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
                    Duration.ofSeconds(Math.max(60, ttlSeconds))
            );
            metricsService.recordAlarmSilenceApproval("approved");
            return new AlarmSilenceApprovalResponse(
                    approval.fingerprint(),
                    true,
                    approval.approvedBy(),
                    approval.reason(),
                    approval.approvedAt(),
                    approval.expiresAt(),
                    silenceApprovalStore.keyFor(approval.fingerprint())
            );
        } catch (RuntimeException ex) {
            metricsService.recordAlarmSilenceApproval("failed");
            throw ex;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
