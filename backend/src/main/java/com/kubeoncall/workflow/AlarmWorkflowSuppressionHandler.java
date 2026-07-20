package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindow;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindowService;
import com.kubeoncall.alarm.suppression.AlarmSuppressionService;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Evaluates the maintenance, configured-rule, and node-noise suppression branches. */
@Service
public class AlarmWorkflowSuppressionHandler {

    private final AlarmMaintenanceWindowService maintenanceWindowService;
    private final AlarmSuppressionService alarmSuppressionService;
    private final AlarmNodeNoiseSuppression nodeNoiseSuppression;
    private final AlarmWorkflowAuditRecorder auditRecorder;
    private final KubeOnCallMetricsService metricsService;

    public AlarmWorkflowSuppressionHandler(
            AlarmMaintenanceWindowService maintenanceWindowService,
            AlarmSuppressionService alarmSuppressionService,
            AlarmNodeNoiseSuppression nodeNoiseSuppression,
            AlarmWorkflowAuditRecorder auditRecorder) {
        this(maintenanceWindowService, alarmSuppressionService, nodeNoiseSuppression, auditRecorder, null);
    }

    @Autowired
    public AlarmWorkflowSuppressionHandler(
            AlarmMaintenanceWindowService maintenanceWindowService,
            AlarmSuppressionService alarmSuppressionService,
            AlarmNodeNoiseSuppression nodeNoiseSuppression,
            AlarmWorkflowAuditRecorder auditRecorder,
            KubeOnCallMetricsService metricsService) {
        this.maintenanceWindowService = maintenanceWindowService;
        this.alarmSuppressionService = alarmSuppressionService;
        this.nodeNoiseSuppression = nodeNoiseSuppression;
        this.auditRecorder = auditRecorder;
        this.metricsService = metricsService;
    }

    public void recordSources(com.kubeoncall.alarm.domain.NormalizedAlarmEvent event) {
        alarmSuppressionService.recordSources(event);
        nodeNoiseSuppression.recordSource(event);
    }

    public Optional<List<NodeResult>> handle(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            Instant startedAt) {
        return handleMaintenanceWindow(preparedAlarm, memoryRecall, startedAt)
                .or(() -> handleConfiguredRule(preparedAlarm, memoryRecall, startedAt))
                .or(() -> handleNodeNoise(preparedAlarm, memoryRecall, startedAt));
    }

    private Optional<List<NodeResult>> handleMaintenanceWindow(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            Instant startedAt) {
        AlarmMaintenanceWindow maintenanceWindow = maintenanceWindowService
                .matchingWindow(preparedAlarm.event(), Instant.now())
                .orElse(null);
        if (maintenanceWindow == null) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = basePayload(preparedAlarm);
        payload.put("suppressedBy", "maintenance_window");
        payload.put("maintenanceWindowId", maintenanceWindow.id());
        payload.put("reason", maintenanceWindow.reason());
        payload.put("startsAt", maintenanceWindow.startsAt());
        payload.put("endsAt", maintenanceWindow.endsAt());
        payload.put("matchers", maintenanceWindow.matchers());
        payload.put("approvalReference", maintenanceWindow.approvalReference());
        NodeResult result = new NodeResult(
                "alarmMaintenanceSuppressed",
                NodeStatus.SUCCESS,
                "Alarm suppressed by approved maintenance window " + maintenanceWindow.id(),
                payload);
        auditRecorder.recordTerminal(
                preparedAlarm,
                memoryRecall,
                result,
                "SUPPRESSED",
                true,
                true,
                result.message(),
                List.of("alarm.suppression", "alarm.suppression.maintenance_window"),
                startedAt,
                Map.of(
                        "suppressed",
                        true,
                        "suppressedBy",
                        "maintenance_window",
                        "maintenanceWindowId",
                        maintenanceWindow.id(),
                        "maintenanceApprovalReference",
                        maintenanceWindow.approvalReference()));
        return suppressed(result);
    }

    private Optional<List<NodeResult>> handleConfiguredRule(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            Instant startedAt) {
        AlarmSuppressionService.SuppressionDecision suppression =
                alarmSuppressionService.evaluate(preparedAlarm.event());
        if (suppression == null || !suppression.suppressed()) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = basePayload(preparedAlarm);
        payload.put("suppressedBy", "configured_rule");
        payload.put("ruleId", suppression.ruleId());
        payload.put("ruleVersion", suppression.ruleVersion());
        payload.put("reason", suppression.reason());
        payload.put("suppressionKey", suppression.suppressionKey());
        payload.put("sourceFingerprint", suppression.sourceFingerprint());
        NodeResult result = new NodeResult(
                "alarmSuppressed", NodeStatus.SUCCESS, "Alarm suppressed by rule " + suppression.ruleId(), payload);
        auditRecorder.recordTerminal(
                preparedAlarm,
                memoryRecall,
                result,
                "SUPPRESSED",
                true,
                false,
                result.message(),
                List.of("alarm.suppression", "alarm.suppression." + suppression.ruleId()),
                startedAt,
                Map.of(
                        "suppressed",
                        true,
                        "suppressedBy",
                        "configured_rule",
                        "suppressionRuleId",
                        suppression.ruleId(),
                        "suppressionRuleVersion",
                        suppression.ruleVersion(),
                        "suppressionKey",
                        suppression.suppressionKey(),
                        "suppressionSourceFingerprint",
                        suppression.sourceFingerprint()));
        return suppressed(result);
    }

    private Optional<List<NodeResult>> handleNodeNoise(
            AlarmEventPreparationService.PreparedAlarm preparedAlarm,
            AlertWorkflowMemory.Recall memoryRecall,
            Instant startedAt) {
        AlarmNodeNoiseSuppression.Decision suppression = nodeNoiseSuppression.evaluate(preparedAlarm.event());
        if (!suppression.suppressed()) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> payload = basePayload(preparedAlarm);
        payload.put("reason", suppression.reason());
        payload.put("nodeName", suppression.nodeName());
        payload.put("suppressionKey", suppression.suppressionKey());
        NodeResult result = new NodeResult(
                "alarmSuppressed", NodeStatus.SUCCESS, "Alarm suppressed: " + suppression.reason(), payload);
        auditRecorder.recordTerminal(
                preparedAlarm,
                memoryRecall,
                result,
                "SUPPRESSED",
                true,
                false,
                result.message(),
                List.of("alarm.suppression", "alarm.suppression.node_not_ready"),
                startedAt,
                Map.of(
                        "suppressed",
                        true,
                        "suppressedBy",
                        "node_not_ready",
                        "suppressionKey",
                        suppression.suppressionKey()));
        return suppressed(result);
    }

    private Optional<List<NodeResult>> suppressed(NodeResult result) {
        if (metricsService != null) {
            metricsService.recordAlarmQuality("suppressed");
        }
        return Optional.of(List.of(result));
    }

    private LinkedHashMap<String, Object> basePayload(AlarmEventPreparationService.PreparedAlarm preparedAlarm) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", preparedAlarm.fingerprint());
        payload.put("suppressed", true);
        payload.put("activeAlarm", auditRecorder.activeAlarmPayload(preparedAlarm.activeState()));
        return payload;
    }
}
