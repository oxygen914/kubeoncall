package com.kubeoncall.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindow;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindowService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.alarm.suppression.AlarmSuppressionService;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;

@Service
public class AlertWorkflowService {

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final AlertWorkflowRunner workflowRunner;
    private final AlarmWorkflowAuditRecorder auditRecorder;
    private final AlarmEventPreparationService eventPreparationService;
    private final AlertWorkflowMemory workflowMemory;
    private final AlarmSilenceApprovalStore silenceApprovalStore;
    private final AlarmRecoveryService alarmRecoveryService;
    private final AlarmMaintenanceWindowService maintenanceWindowService;
    private final AlarmSuppressionService alarmSuppressionService;
    private final AlarmNodeNoiseSuppression nodeNoiseSuppression;
    private final AlarmWorkflowEscalation workflowEscalation;

    public AlertWorkflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowRunner workflowRunner,
            AlarmWorkflowAuditRecorder auditRecorder,
            AlarmEventPreparationService eventPreparationService,
            AlertWorkflowMemory workflowMemory,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmRecoveryService alarmRecoveryService,
            AlarmNodeNoiseSuppression nodeNoiseSuppression,
            AlarmWorkflowEscalation workflowEscalation,
            AlarmMaintenanceWindowService maintenanceWindowService,
            AlarmSuppressionService alarmSuppressionService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.workflowRunner = workflowRunner;
        this.auditRecorder = auditRecorder;
        this.eventPreparationService = eventPreparationService;
        this.workflowMemory = workflowMemory;
        this.silenceApprovalStore = silenceApprovalStore;
        this.alarmRecoveryService = alarmRecoveryService;
        this.nodeNoiseSuppression = nodeNoiseSuppression;
        this.workflowEscalation = workflowEscalation;
        this.maintenanceWindowService = maintenanceWindowService;
        this.alarmSuppressionService = alarmSuppressionService;
    }

    /**
     * Legacy entry point retained for backwards compatibility. Delegates to
     * {@link #process(NormalizedAlarmEvent)} with a minimal normalized event synthesized from the
     * legacy {@link AlarmEvent}, so the new policy/dedup path is exercised uniformly.
     */
    public List<NodeResult> process(AlarmEvent alarmEvent) {
        return process(toNormalized(alarmEvent));
    }

    /**
     * Standard entry point: normalize-independent processing of a {@link NormalizedAlarmEvent}.
     *
     * <p>Dedup uses the event's stable {@code fingerprint} (never a raw {@code dedupKey} that could
     * be null), the policy engine runs before the workflow so nodes can read the matched policy, and
     * both are placed on the {@link AlertWorkflowContext}.
     */
    public List<NodeResult> process(NormalizedAlarmEvent event) {
        Instant startedAt = Instant.now();
        AlarmEventPreparationService.PreparedAlarm preparedAlarm = eventPreparationService.prepare(event);
        event = preparedAlarm.event();
        String fingerprint = preparedAlarm.fingerprint();
        AlarmEvaluationResult evaluation = preparedAlarm.evaluation();
        ActiveAlarmState activeState = preparedAlarm.activeState();
        if (event.status() != AlarmStatus.RESOLVED) {
            alarmRecoveryService.cancelIfPending(fingerprint);
        }
        AlertWorkflowMemory.Recall memoryRecall = workflowMemory.recall(event);
        alarmSuppressionService.recordSources(event);
        nodeNoiseSuppression.recordSource(event);
        if (event.status() == AlarmStatus.RESOLVED) {
            AlarmPolicy recoveryPolicy = evaluation.matchedPolicy();
            if (recoveryPolicy == null && activeState != null) {
                recoveryPolicy = eventPreparationService
                        .findPolicyById(activeState.policyId())
                        .orElse(null);
            }
            AlarmRecoveryState recoveryState = alarmRecoveryService.begin(event, recoveryPolicy, activeState);
            LinkedHashMap<String, Object> payload = auditRecorder.recoveryPayload(recoveryState);
            payload.put("activeAlarm", auditRecorder.activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmRecoveryPending",
                    NodeStatus.SUCCESS,
                    recoveryState.manualConfirmationRequired()
                            ? "Alarm recovery is stable-window pending and requires manual confirmation"
                            : "Alarm recovery is pending the configured stability window",
                    payload);
            recordAudit(
                    event,
                    evaluation,
                    activeState,
                    memoryRecall,
                    null,
                    List.of(result),
                    "RECOVERY_PENDING",
                    false,
                    recoveryState.manualConfirmationRequired(),
                    result.message(),
                    null,
                    List.of("alarm.recovery.candidate"),
                    startedAt,
                    Map.of(
                            "recoveryStatus", recoveryState.status(),
                            "recoveryConfirmAfter", recoveryState.confirmAfter().toString(),
                            "manualRecoveryConfirmation", recoveryState.manualConfirmationRequired()));
            return List.of(result);
        }
        AlarmMaintenanceWindow maintenanceWindow =
                maintenanceWindowService.matchingWindow(event, Instant.now()).orElse(null);
        if (maintenanceWindow != null) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("suppressed", true);
            payload.put("suppressedBy", "maintenance_window");
            payload.put("maintenanceWindowId", maintenanceWindow.id());
            payload.put("reason", maintenanceWindow.reason());
            payload.put("startsAt", maintenanceWindow.startsAt());
            payload.put("endsAt", maintenanceWindow.endsAt());
            payload.put("matchers", maintenanceWindow.matchers());
            payload.put("approvalReference", maintenanceWindow.approvalReference());
            payload.put("activeAlarm", auditRecorder.activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmMaintenanceSuppressed",
                    NodeStatus.SUCCESS,
                    "Alarm suppressed by approved maintenance window " + maintenanceWindow.id(),
                    payload);
            recordAudit(
                    event,
                    evaluation,
                    activeState,
                    memoryRecall,
                    null,
                    List.of(result),
                    "SUPPRESSED",
                    true,
                    true,
                    result.message(),
                    null,
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
            return List.of(result);
        }
        AlarmSuppressionService.SuppressionDecision configuredSuppression = alarmSuppressionService.evaluate(event);
        if (configuredSuppression != null && configuredSuppression.suppressed()) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("suppressed", true);
            payload.put("suppressedBy", "configured_rule");
            payload.put("ruleId", configuredSuppression.ruleId());
            payload.put("ruleVersion", configuredSuppression.ruleVersion());
            payload.put("reason", configuredSuppression.reason());
            payload.put("suppressionKey", configuredSuppression.suppressionKey());
            payload.put("sourceFingerprint", configuredSuppression.sourceFingerprint());
            payload.put("activeAlarm", auditRecorder.activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmSuppressed",
                    NodeStatus.SUCCESS,
                    "Alarm suppressed by rule " + configuredSuppression.ruleId(),
                    payload);
            recordAudit(
                    event,
                    evaluation,
                    activeState,
                    memoryRecall,
                    null,
                    List.of(result),
                    "SUPPRESSED",
                    true,
                    false,
                    result.message(),
                    null,
                    List.of("alarm.suppression", "alarm.suppression." + configuredSuppression.ruleId()),
                    startedAt,
                    Map.of(
                            "suppressed", true,
                            "suppressedBy", "configured_rule",
                            "suppressionRuleId", configuredSuppression.ruleId(),
                            "suppressionRuleVersion", configuredSuppression.ruleVersion(),
                            "suppressionKey", configuredSuppression.suppressionKey(),
                            "suppressionSourceFingerprint", configuredSuppression.sourceFingerprint()));
            return List.of(result);
        }
        AlarmNodeNoiseSuppression.Decision suppression = nodeNoiseSuppression.evaluate(event);
        if (suppression.suppressed()) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("suppressed", true);
            payload.put("reason", suppression.reason());
            payload.put("nodeName", suppression.nodeName());
            payload.put("suppressionKey", suppression.suppressionKey());
            payload.put("activeAlarm", auditRecorder.activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmSuppressed", NodeStatus.SUCCESS, "Alarm suppressed: " + suppression.reason(), payload);
            recordAudit(
                    event,
                    evaluation,
                    activeState,
                    memoryRecall,
                    null,
                    List.of(result),
                    "SUPPRESSED",
                    true,
                    false,
                    result.message(),
                    null,
                    List.of("alarm.suppression", "alarm.suppression.node_not_ready"),
                    startedAt,
                    Map.of(
                            "suppressed",
                            true,
                            "suppressedBy",
                            "node_not_ready",
                            "suppressionKey",
                            suppression.suppressionKey()));
            return List.of(result);
        }

        String dedupKey = "alarm-dedup:" + fingerprint;
        Boolean accepted = redisTemplate
                .opsForValue()
                .setIfAbsent(
                        dedupKey,
                        event.alarmId() == null ? fingerprint : event.alarmId(),
                        dedupTtl(evaluation.finalSeverity()));
        if (Boolean.FALSE.equals(accepted)) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("dedupKey", fingerprint);
            payload.put("dedupHit", true);
            payload.put("activeAlarm", auditRecorder.activeAlarmPayload(activeState));
            NodeResult result = new NodeResult("alarmDedup", NodeStatus.FAILURE, "Duplicate alarm ignored", payload);
            recordAudit(
                    event,
                    evaluation,
                    activeState,
                    memoryRecall,
                    null,
                    List.of(result),
                    "DEDUP_HIT",
                    true,
                    false,
                    "Duplicate alarm ignored: fingerprint=" + fingerprint,
                    null,
                    List.of("alarm.dedup", "alarm.dedup.hit"),
                    startedAt,
                    Map.of("dedupHit", true));
            return List.of(result);
        }

        AlarmEvent legacy = toLegacy(event, evaluation);

        AlertWorkflowContext context = new AlertWorkflowContext(legacy, event, evaluation, startedAt);
        context.putAttribute("activeAlarm", activeState);
        workflowMemory.attach(context, memoryRecall);
        attachSilenceApproval(fingerprint, context);
        workflowRunner.run(context, evaluation);
        workflowEscalation.addResult(event, evaluation, activeState, fingerprint, context);

        List<NodeResult> results = context.getNodeResults();
        if (results.isEmpty()) {
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("policyId", evaluation.policyId());
            payload.put("policyMatched", evaluation.matched());
            context.addNodeResult(
                    new NodeResult("workflowEmpty", NodeStatus.SUCCESS, "No workflow nodes configured", payload));
            results = context.getNodeResults();
        }
        NodeResult latest = results.get(results.size() - 1);
        boolean noIssues =
                context.getFailedNodes().isEmpty() && context.getSkippedNodes().isEmpty();
        String status = noIssues ? "SUCCESS" : "DEGRADED";
        String failureReason = noIssues
                ? null
                : "Failed nodes: " + String.join(", ", context.getFailedNodes()) + "; Skipped nodes: "
                        + String.join(", ", context.getSkippedNodes());
        LinkedHashSet<String> toolNames = new LinkedHashSet<>();
        for (NodeResult result : results) {
            if (result.payload() == null) {
                continue;
            }
            Object executorKind = result.payload().get("executorKind");
            Object action = result.payload().get("action");
            if (executorKind != null && action != null) {
                toolNames.add(executorKind + "." + action);
            }
            Object resultPayload = result.payload().get("result");
            if (resultPayload instanceof Map<?, ?> map) {
                Object nestedExecutor = map.get("executor");
                Object nestedAction = map.get("action");
                if (nestedExecutor != null && nestedAction != null) {
                    toolNames.add(nestedExecutor + "." + nestedAction);
                }
            }
            if (Boolean.TRUE.equals(result.payload().get("dedupHit"))) {
                toolNames.add("alarm.dedup.hit");
            }
        }
        if (!memoryRecall.entries().isEmpty()) {
            toolNames.add("memory.alert.recall");
        }
        if (!memoryRecall.warning().isBlank()) {
            toolNames.add("memory.alert.recall_failed");
        }
        if (workflowMemory.consumedCount(context) > 0) {
            toolNames.add("memory.alert.consume");
        }
        if (toolNames.isEmpty()) {
            toolNames.addAll(results.stream().map(NodeResult::nodeName).toList());
        }
        String handlingSummary =
                auditRecorder.summary(event, evaluation, activeState, memoryRecall, context, latest, status);
        workflowMemory.extract(event, handlingSummary, context);
        recordAudit(
                event,
                evaluation,
                activeState,
                memoryRecall,
                context,
                results,
                status,
                noIssues,
                false,
                handlingSummary,
                failureReason,
                new ArrayList<>(toolNames),
                startedAt,
                Map.of("workflowStatus", status));
        return results;
    }

    private Duration dedupTtl(AlarmSeverity severity) {
        long seconds =
                switch (severity == null ? AlarmSeverity.P3 : severity) {
                    case P0 -> properties.getAlarm().getP0DedupTtlSeconds();
                    case P1 -> properties.getAlarm().getP1DedupTtlSeconds();
                    case P2 -> properties.getAlarm().getP2DedupTtlSeconds();
                    case P3 -> properties.getAlarm().getP3DedupTtlSeconds();
                    case INFO -> properties.getAlarm().getInfoDedupTtlSeconds();
                };
        if (seconds <= 0) {
            seconds = properties.getWorkflow().getAlarmDedupTtlSeconds();
        }
        return Duration.ofSeconds(seconds);
    }

    private void recordAudit(
            NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState activeState,
            AlertWorkflowMemory.Recall memoryRecall,
            AlertWorkflowContext context,
            List<NodeResult> results,
            String status,
            boolean autoHandled,
            boolean approvalRequired,
            String summary,
            String failureReason,
            List<String> tools,
            Instant startedAt,
            Map<String, Object> extras) {
        auditRecorder.record(new AlarmWorkflowAuditRecorder.AuditRequest(
                event.alarmId() == null ? event.fingerprint() : event.alarmId(),
                status,
                autoHandled,
                approvalRequired,
                summary,
                failureReason,
                tools,
                startedAt,
                event,
                evaluation,
                activeState,
                memoryRecall,
                context,
                results,
                extras));
    }

    private void attachSilenceApproval(String fingerprint, AlertWorkflowContext context) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        try {
            silenceApprovalStore.find(fingerprint).ifPresent(approval -> {
                context.putAttribute("silenceApproved", true);
                context.putAttribute("silenceApprovedBy", approval.approvedBy());
                context.putAttribute("silenceApprovalReason", approval.reason());
                context.putAttribute(
                        "silenceApprovalExpiresAt",
                        approval.expiresAt() == null
                                ? null
                                : approval.expiresAt().toString());
                context.putAttribute("silenceApprovalKey", silenceApprovalStore.keyFor(fingerprint));
            });
        } catch (RuntimeException ex) {
            context.putAttribute("silenceApprovalWarning", "silence approval lookup failed: " + ex.getMessage());
        }
    }

    private static NormalizedAlarmEvent toNormalized(AlarmEvent alarmEvent) {
        // A minimal normalized event preserving the legacy fields. The fingerprint falls back to the
        // dedupKey when present and to the alarmId otherwise — this path is only reached by legacy
        // callers that did not go through AlarmNormalizer.
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

    private static AlarmEvent toLegacy(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        // Provide a legacy AlarmEvent view for nodes that have not yet been migrated. The severity is
        // the policy-computed final severity when available so legacy nodes see the governed value.
        String severity = evaluation != null && evaluation.finalSeverity() != null
                ? evaluation.finalSeverity().name()
                : (event.rawSeverity() != null ? event.rawSeverity() : null);
        return new AlarmEvent(
                event.alarmId(),
                event.fingerprint(),
                event.source(),
                severity,
                event.resourceName(),
                event.summary(),
                event.occurredAt(),
                event.metadata());
    }
}
