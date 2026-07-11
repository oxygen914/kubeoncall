package com.kubeoncall.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.recovery.AlarmRecoveryService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.alarm.escalation.AlarmEscalationService;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindow;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindowService;
import com.kubeoncall.alarm.suppression.AlarmSuppressionService;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.memory.AlertMemoryService;
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryExtractor;
import com.kubeoncall.service.ExecutionAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class AlertWorkflowService {

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;
    private final AlertWorkflowFactory alertWorkflowFactory;
    private final WorkflowNodeExecutor workflowNodeExecutor;
    private final ExecutionAuditService executionAuditService;
    private final AlarmPolicyEngine alarmPolicyEngine;
    private final ActiveAlarmStore activeAlarmStore;
    private final AlertMemoryService alertMemoryService;
    private final MemoryExtractor memoryExtractor;
    private final AlarmSilenceApprovalStore silenceApprovalStore;
    private final AlarmRecoveryService alarmRecoveryService;
    private final AlarmEscalationService alarmEscalationService;
    private final AlarmMaintenanceWindowService maintenanceWindowService;
    private final AlarmSuppressionService alarmSuppressionService;
    private final AlarmFingerprintService alarmFingerprintService = new AlarmFingerprintService();

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, new ActiveAlarmStore(redisTemplate, new ObjectMapper(), properties),
                null, MemoryExtractor.noop(), null, null, null);
    }

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore,
                                AlertMemoryService alertMemoryService,
                                MemoryExtractor memoryExtractor) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, activeAlarmStore, alertMemoryService, memoryExtractor, null, null, null);
    }

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore,
                                AlertMemoryService alertMemoryService,
                                MemoryExtractor memoryExtractor,
                                AlarmSilenceApprovalStore silenceApprovalStore) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, activeAlarmStore, alertMemoryService, memoryExtractor, silenceApprovalStore, null, null);
    }

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore,
                                AlertMemoryService alertMemoryService,
                                MemoryExtractor memoryExtractor,
                                AlarmSilenceApprovalStore silenceApprovalStore,
                                AlarmRecoveryService alarmRecoveryService) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, activeAlarmStore, alertMemoryService, memoryExtractor, silenceApprovalStore,
                alarmRecoveryService, null);
    }

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore,
                                AlertMemoryService alertMemoryService,
                                MemoryExtractor memoryExtractor,
                                AlarmSilenceApprovalStore silenceApprovalStore,
                                AlarmRecoveryService alarmRecoveryService,
                                AlarmEscalationService alarmEscalationService) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, activeAlarmStore, alertMemoryService, memoryExtractor, silenceApprovalStore,
                alarmRecoveryService, alarmEscalationService, null);
    }

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore,
                                AlertMemoryService alertMemoryService,
                                MemoryExtractor memoryExtractor,
                                AlarmSilenceApprovalStore silenceApprovalStore,
                                AlarmRecoveryService alarmRecoveryService,
                                AlarmEscalationService alarmEscalationService,
                                AlarmMaintenanceWindowService maintenanceWindowService) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, activeAlarmStore, alertMemoryService, memoryExtractor, silenceApprovalStore,
                alarmRecoveryService, alarmEscalationService, maintenanceWindowService, null);
    }

    @Autowired
    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore,
                                AlertMemoryService alertMemoryService,
                                MemoryExtractor memoryExtractor,
                                AlarmSilenceApprovalStore silenceApprovalStore,
                                AlarmRecoveryService alarmRecoveryService,
                                AlarmEscalationService alarmEscalationService,
                                AlarmMaintenanceWindowService maintenanceWindowService,
                                AlarmSuppressionService alarmSuppressionService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.alertWorkflowFactory = alertWorkflowFactory;
        this.workflowNodeExecutor = workflowNodeExecutor;
        this.executionAuditService = executionAuditService;
        this.alarmPolicyEngine = alarmPolicyEngine;
        this.activeAlarmStore = activeAlarmStore;
        this.alertMemoryService = alertMemoryService;
        this.memoryExtractor = memoryExtractor == null ? MemoryExtractor.noop() : memoryExtractor;
        this.silenceApprovalStore = silenceApprovalStore;
        this.alarmRecoveryService = alarmRecoveryService;
        this.alarmEscalationService = alarmEscalationService;
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
        String fingerprint = event.fingerprint();
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = alarmFingerprintService.fingerprint(event);
            event = withFingerprint(event, fingerprint);
        }
        AlarmEvaluationResult evaluation = alarmPolicyEngine.evaluate(event);
        ActiveAlarmState activeState = activeAlarmStore.record(event, evaluation, fingerprint);
        if (event.status() != AlarmStatus.RESOLVED && alarmRecoveryService != null) {
            alarmRecoveryService.cancelIfPending(fingerprint);
        }
        MemoryRecallResult memoryRecall = recallAlertMemory(event);
        if (alarmSuppressionService == null) {
            recordNodeNotReadySuppression(event);
        } else {
            alarmSuppressionService.recordSources(event);
        }
        if (event.status() == AlarmStatus.RESOLVED) {
            if (alarmRecoveryService != null) {
                AlarmPolicy recoveryPolicy = evaluation.matchedPolicy();
                if (recoveryPolicy == null && activeState != null) {
                    recoveryPolicy = alarmPolicyEngine.findPolicyById(activeState.policyId()).orElse(null);
                }
                AlarmRecoveryState recoveryState = alarmRecoveryService.begin(event, recoveryPolicy, activeState);
                LinkedHashMap<String, Object> payload = recoveryPayload(recoveryState);
                payload.put("activeAlarm", activeAlarmPayload(activeState));
                NodeResult result = new NodeResult(
                        "alarmRecoveryPending",
                        NodeStatus.SUCCESS,
                        recoveryState.manualConfirmationRequired()
                                ? "Alarm recovery is stable-window pending and requires manual confirmation"
                                : "Alarm recovery is pending the configured stability window",
                        payload
                );
                executionAuditService.recordAlarmExecution(
                        event.alarmId() == null ? fingerprint : event.alarmId(),
                        "RECOVERY_PENDING",
                        false,
                        recoveryState.manualConfirmationRequired(),
                        result.message(),
                        null,
                        List.of("alarm.recovery.candidate"),
                        startedAt,
                        buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, null, List.of(result), Map.of(
                                "recoveryStatus", recoveryState.status(),
                                "recoveryConfirmAfter", recoveryState.confirmAfter().toString(),
                                "manualRecoveryConfirmation", recoveryState.manualConfirmationRequired()
                        ))
                );
                return List.of(result);
            }
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("activeAlarm", activeAlarmPayload(activeState));
            payload.put("reason", "RECOVERY_SERVICE_UNAVAILABLE");
            NodeResult result = new NodeResult(
                    "alarmRecoveryUnavailable",
                    NodeStatus.FAILURE,
                    "Alarm recovery cannot be confirmed because the recovery service is unavailable",
                    payload
            );
            executionAuditService.recordAlarmExecution(
                    event.alarmId() == null ? fingerprint : event.alarmId(),
                    "DEGRADED",
                    false,
                    false,
                    result.message(),
                    "RECOVERY_SERVICE_UNAVAILABLE",
                    List.of("alarm.recovery.candidate"),
                    startedAt,
                    buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, null, List.of(result), Map.of(
                            "recoveryStatus", "UNAVAILABLE",
                            "recovered", false
                    ))
            );
            return List.of(result);
        }
        AlarmMaintenanceWindow maintenanceWindow = maintenanceWindowService == null
                ? null
                : maintenanceWindowService.matchingWindow(event, Instant.now()).orElse(null);
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
            payload.put("activeAlarm", activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmMaintenanceSuppressed",
                    NodeStatus.SUCCESS,
                    "Alarm suppressed by approved maintenance window " + maintenanceWindow.id(),
                    payload
            );
            executionAuditService.recordAlarmExecution(
                    event.alarmId() == null ? fingerprint : event.alarmId(),
                    "SUPPRESSED",
                    true,
                    true,
                    result.message(),
                    null,
                    List.of("alarm.suppression", "alarm.suppression.maintenance_window"),
                    startedAt,
                    buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, null, List.of(result), Map.of(
                            "suppressed", true,
                            "suppressedBy", "maintenance_window",
                            "maintenanceWindowId", maintenanceWindow.id(),
                            "maintenanceApprovalReference", maintenanceWindow.approvalReference()
                    ))
            );
            return List.of(result);
        }
        AlarmSuppressionService.SuppressionDecision configuredSuppression = alarmSuppressionService == null
                ? null
                : alarmSuppressionService.evaluate(event);
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
            payload.put("activeAlarm", activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmSuppressed",
                    NodeStatus.SUCCESS,
                    "Alarm suppressed by rule " + configuredSuppression.ruleId(),
                    payload
            );
            executionAuditService.recordAlarmExecution(
                    event.alarmId() == null ? fingerprint : event.alarmId(),
                    "SUPPRESSED",
                    true,
                    false,
                    result.message(),
                    null,
                    List.of("alarm.suppression", "alarm.suppression." + configuredSuppression.ruleId()),
                    startedAt,
                    buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, null, List.of(result), Map.of(
                            "suppressed", true,
                            "suppressedBy", "configured_rule",
                            "suppressionRuleId", configuredSuppression.ruleId(),
                            "suppressionRuleVersion", configuredSuppression.ruleVersion(),
                            "suppressionKey", configuredSuppression.suppressionKey(),
                            "suppressionSourceFingerprint", configuredSuppression.sourceFingerprint()
                    ))
            );
            return List.of(result);
        }
        SuppressionDecision suppression = alarmSuppressionService == null
                ? suppressPodNoise(event)
                : SuppressionDecision.none();
        if (suppression.suppressed()) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("suppressed", true);
            payload.put("reason", suppression.reason());
            payload.put("nodeName", suppression.nodeName());
            payload.put("suppressionKey", suppression.suppressionKey());
            payload.put("activeAlarm", activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmSuppressed",
                    NodeStatus.SUCCESS,
                    "Alarm suppressed: " + suppression.reason(),
                    payload
            );
            executionAuditService.recordAlarmExecution(
                    event.alarmId() == null ? fingerprint : event.alarmId(),
                    "SUPPRESSED",
                    true,
                    false,
                    result.message(),
                    null,
                    List.of("alarm.suppression", "alarm.suppression.node_not_ready"),
                    startedAt,
                    buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, null, List.of(result), Map.of(
                            "suppressed", true,
                            "suppressedBy", "node_not_ready",
                            "suppressionKey", suppression.suppressionKey()
                    ))
            );
            return List.of(result);
        }

        String dedupKey = "alarm-dedup:" + fingerprint;
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                dedupKey,
                event.alarmId() == null ? fingerprint : event.alarmId(),
                dedupTtl(evaluation.finalSeverity())
        );
        if (Boolean.FALSE.equals(accepted)) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("dedupKey", fingerprint);
            payload.put("dedupHit", true);
            payload.put("activeAlarm", activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmDedup",
                    NodeStatus.FAILURE,
                    "Duplicate alarm ignored",
                    payload
            );
            executionAuditService.recordAlarmExecution(
                    event.alarmId() == null ? fingerprint : event.alarmId(),
                    "DEDUP_HIT",
                    true,
                    false,
                    "Duplicate alarm ignored: fingerprint=" + fingerprint,
                    null,
                    List.of("alarm.dedup", "alarm.dedup.hit"),
                    startedAt,
                    buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, null, List.of(result), Map.of("dedupHit", true))
            );
            return List.of(result);
        }

        AlarmEvent legacy = toLegacy(event, evaluation);

        AlertWorkflowContext context = new AlertWorkflowContext(legacy, event, evaluation, startedAt);
        context.putAttribute("activeAlarm", activeState);
        context.putAttribute("alertMemories", memoryPayload(memoryRecall.entries()));
        context.putAttribute("alertMemoryEntries", memoryRecall.entries());
        attachSilenceApproval(fingerprint, context);
        if (!memoryRecall.warning().isBlank()) {
            context.putAttribute("alertMemoryWarning", memoryRecall.warning());
        }
        Duration nodeTimeout = Duration.ofMillis(properties.getWorkflow().getNodeTimeoutMillis());
        List<AlertWorkflowDefinition> workflow = evaluation.workflowTemplate() == null
                ? alertWorkflowFactory.buildWorkflow()
                : alertWorkflowFactory.buildWorkflow(evaluation.workflowTemplate());
        for (AlertWorkflowDefinition definition : workflow) {
            if (context.isTerminated()) {
                break;
            }
            List<String> missingDependencies = definition.dependencies().stream()
                    .filter(dependency -> !context.getCompletedNodes().contains(dependency))
                    .toList();
            if (!missingDependencies.isEmpty()) {
                context.setDegraded(true);
                context.addSkippedNode(definition.name());
                context.addNodeResult(new NodeResult(
                        definition.name(),
                        NodeStatus.FAILURE,
                        "Skipped due to unmet dependencies: " + String.join(", ", missingDependencies),
                        Map.of(
                                "skipped", true,
                                "dependencies", definition.dependencies(),
                                "missingDependencies", missingDependencies,
                                "failedNodes", context.getFailedNodes(),
                                "completedNodes", context.getCompletedNodes()
                        )
                ));
                continue;
            }
            workflowNodeExecutor.execute(definition, context, nodeTimeout);
        }
        maybeAddEscalationResult(event, evaluation, activeState, fingerprint, context);

        List<NodeResult> results = context.getNodeResults();
        if (results.isEmpty()) {
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("policyId", evaluation.policyId());
            payload.put("policyMatched", evaluation.matched());
            context.addNodeResult(new NodeResult(
                    "workflowEmpty",
                    NodeStatus.SUCCESS,
                    "No workflow nodes configured",
                    payload
            ));
            results = context.getNodeResults();
        }
        NodeResult latest = results.get(results.size() - 1);
        boolean noIssues = context.getFailedNodes().isEmpty() && context.getSkippedNodes().isEmpty();
        String status = noIssues ? "SUCCESS" : "DEGRADED";
        String failureReason = noIssues
                ? null
                : "Failed nodes: " + String.join(", ", context.getFailedNodes())
                + "; Skipped nodes: " + String.join(", ", context.getSkippedNodes());
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
        if (consumedMemoryCount(context) > 0) {
            toolNames.add("memory.alert.consume");
        }
        if (toolNames.isEmpty()) {
            toolNames.addAll(results.stream().map(NodeResult::nodeName).toList());
        }
        String handlingSummary = buildAlarmHandlingSummary(event, evaluation, activeState, memoryRecall, context, latest, status);
        extractAlarmMemory(event, handlingSummary, context);
        executionAuditService.recordAlarmExecution(
                event.alarmId() == null ? fingerprint : event.alarmId(),
                status,
                noIssues,
                false,
                handlingSummary,
                failureReason,
                new ArrayList<>(toolNames),
                startedAt,
                buildAlarmAuditMetadata(event, evaluation, activeState, memoryRecall, context, results, Map.of("workflowStatus", status))
        );
        return results;
    }

    private Duration dedupTtl(AlarmSeverity severity) {
        long seconds = switch (severity == null ? AlarmSeverity.P3 : severity) {
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

    private void recordNodeNotReadySuppression(NormalizedAlarmEvent event) {
        if (!isNodeNotReady(event)) {
            return;
        }
        String nodeName = event.resourceName();
        if (nodeName == null || nodeName.isBlank()) {
            return;
        }
        String key = nodeSuppressionKey(event.cluster(), nodeName);
        if (event.status() == AlarmStatus.RESOLVED) {
            redisTemplate.delete(key);
            return;
        }
        redisTemplate.opsForValue().set(
                key,
                event.fingerprint() == null ? "NodeNotReady" : event.fingerprint(),
                Duration.ofSeconds(Math.max(60, properties.getAlarm().getNodeNotReadySuppressionTtlSeconds()))
        );
    }

    private SuppressionDecision suppressPodNoise(NormalizedAlarmEvent event) {
        if (event.resourceType() != AlarmResourceType.POD) {
            return SuppressionDecision.none();
        }
        String nodeName = nodeName(event);
        if (nodeName == null || nodeName.isBlank()) {
            return SuppressionDecision.none();
        }
        String key = nodeSuppressionKey(event.cluster(), nodeName);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return SuppressionDecision.none();
        }
        return new SuppressionDecision(true, nodeName, key, "NodeNotReady is active for node " + nodeName);
    }

    private boolean isNodeNotReady(NormalizedAlarmEvent event) {
        String alertName = event.alertName() == null ? "" : event.alertName();
        return event.resourceType() == AlarmResourceType.NODE
                && alertName.toLowerCase().contains("nodenotready");
    }

    private String nodeName(NormalizedAlarmEvent event) {
        String value = stringValue(event.labels().get("node"));
        if (value == null) {
            value = stringValue(event.labels().get("nodeName"));
        }
        if (value == null) {
            value = stringValue(event.labels().get("kubernetes.io/hostname"));
        }
        if (value == null) {
            value = stringValue(event.annotations().get("node"));
        }
        if (value == null) {
            value = stringValue(event.metadata().get("node"));
        }
        if (value == null) {
            value = stringValue(event.metadata().get("nodeName"));
        }
        return value;
    }

    private String nodeSuppressionKey(String cluster, String nodeName) {
        String normalizedCluster = cluster == null || cluster.isBlank() ? "default" : cluster;
        return "alarm-suppression:node:" + normalizedCluster + ":" + nodeName;
    }

    private void maybeAddEscalationResult(NormalizedAlarmEvent event,
                                          AlarmEvaluationResult evaluation,
                                          ActiveAlarmState activeState,
                                          String fingerprint,
                                          AlertWorkflowContext context) {
        AlarmSeverity severity = evaluation == null ? null : evaluation.finalSeverity();
        if (severity != AlarmSeverity.P0 && severity != AlarmSeverity.P1) {
            return;
        }
        long threshold = severity == AlarmSeverity.P0
                ? properties.getAlarm().getP0EscalationCount()
                : properties.getAlarm().getP1EscalationCount();
        if (activeState == null || activeState.count() < Math.max(1, threshold)) {
            return;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey("alarm-ack:" + fingerprint))) {
            return;
        }
        String escalationKey = "alarm-escalation:" + fingerprint;
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                escalationKey,
                severity.name(),
                Duration.ofSeconds(Math.max(60, properties.getAlarm().getEscalationTtlSeconds()))
        );
        if (Boolean.FALSE.equals(accepted)) {
            return;
        }
        if (alarmEscalationService == null) {
            context.addNodeResult(new NodeResult(
                    "alarmEscalation",
                    NodeStatus.FAILURE,
                    "Alarm escalation integration is unavailable",
                    Map.of(
                            "fingerprint", fingerprint,
                            "severity", severity.name(),
                            "count", activeState.count(),
                            "threshold", threshold,
                            "reason", "ESCALATION_SERVICE_UNAVAILABLE"
                    )
            ));
            return;
        }
        context.addNodeResult(alarmEscalationService.escalate(event, evaluation, activeState.count(), threshold));
    }

    private MemoryRecallResult recallAlertMemory(NormalizedAlarmEvent event) {
        if (alertMemoryService == null) {
            return new MemoryRecallResult(List.of(), "");
        }
        try {
            List<MemoryEntry> entries = alertMemoryService.recall(event);
            return new MemoryRecallResult(entries == null ? List.of() : entries, "");
        } catch (RuntimeException ex) {
            return new MemoryRecallResult(List.of(), "alert memory recall failed: " + ex.getMessage());
        }
    }

    private void attachSilenceApproval(String fingerprint, AlertWorkflowContext context) {
        if (silenceApprovalStore == null || fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        try {
            silenceApprovalStore.find(fingerprint).ifPresent(approval -> {
                context.putAttribute("silenceApproved", true);
                context.putAttribute("silenceApprovedBy", approval.approvedBy());
                context.putAttribute("silenceApprovalReason", approval.reason());
                context.putAttribute("silenceApprovalExpiresAt", approval.expiresAt() == null ? null : approval.expiresAt().toString());
                context.putAttribute("silenceApprovalKey", silenceApprovalStore.keyFor(fingerprint));
            });
        } catch (RuntimeException ex) {
            context.putAttribute("silenceApprovalWarning", "silence approval lookup failed: " + ex.getMessage());
        }
    }

    private void extractAlarmMemory(NormalizedAlarmEvent event, String summary, AlertWorkflowContext context) {
        try {
            memoryExtractor.extractFromAlarm(event, summary);
        } catch (RuntimeException ex) {
            if (context != null) {
                context.putAttribute("alertMemoryExtractionWarning", "alert memory extraction failed: " + ex.getMessage());
            }
        }
    }

    private String buildAlarmHandlingSummary(NormalizedAlarmEvent event,
                                             AlarmEvaluationResult evaluation,
                                             ActiveAlarmState activeState,
                                             MemoryRecallResult memoryRecall,
                                             AlertWorkflowContext context,
                                             NodeResult latest,
                                             String outcome) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, "alert", event.alertName());
        appendPart(builder, "fingerprint", event.fingerprint());
        appendPart(builder, "outcome", outcome);
        appendPart(builder, "severity", evaluation == null || evaluation.finalSeverity() == null ? event.rawSeverity() : evaluation.finalSeverity().name());
        appendPart(builder, "policy", evaluation == null ? null : evaluation.policyId());
        appendPart(builder, "template", evaluation == null ? null : evaluation.workflowTemplate());
        appendPart(builder, "resourceType", event.resourceType());
        appendPart(builder, "resource", event.resourceName());
        appendPart(builder, "service", event.service());
        appendPart(builder, "cluster", event.cluster());
        appendPart(builder, "namespace", event.namespace());
        appendPart(builder, "activeCount", activeState == null ? null : activeState.count());
        appendPart(builder, "memoryRecallCount", memoryRecall == null ? 0 : memoryRecall.entries().size());
        if (context != null) {
            appendPart(builder, "failedNodes", context.getFailedNodes());
            appendPart(builder, "skippedNodes", context.getSkippedNodes());
            appendPart(builder, "degraded", context.isDegraded());
            appendPart(builder, "silenceApproved", context.getAttribute("silenceApproved"));
            appendPart(builder, "memoryConsumedCount", context.getAttribute("alertMemoryConsumed"));
            appendPart(builder, "repeatIncident", context.getAttribute("repeatIncident"));
        }
        if (latest != null) {
            appendPart(builder, "latestNode", latest.nodeName());
            appendPart(builder, "latestStatus", latest.status());
            appendPart(builder, "latestMessage", latest.message());
        }
        return builder.toString();
    }

    private Map<String, Object> buildAlarmAuditMetadata(NormalizedAlarmEvent event,
                                                        AlarmEvaluationResult evaluation,
                                                        ActiveAlarmState activeState,
                                                        MemoryRecallResult memoryRecall,
                                                        AlertWorkflowContext context,
                                                        List<NodeResult> results,
                                                        Map<String, Object> extras) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "fingerprint", event.fingerprint());
        putIfPresent(metadata, "alarmId", event.alarmId());
        putIfPresent(metadata, "alertName", event.alertName());
        putIfPresent(metadata, "source", event.source());
        putIfPresent(metadata, "status", event.status());
        putIfPresent(metadata, "resourceType", event.resourceType());
        putIfPresent(metadata, "resourceName", event.resourceName());
        putIfPresent(metadata, "cluster", event.cluster());
        putIfPresent(metadata, "namespace", event.namespace());
        putIfPresent(metadata, "service", event.service());
        putIfPresent(metadata, "metricName", event.metricName());
        putIfPresent(metadata, "runbookId", event.runbookId());
        if (evaluation != null) {
            putIfPresent(metadata, "policyId", evaluation.policyId());
            putIfPresent(metadata, "policyVersion",
                    evaluation.matchedPolicy() == null ? null : evaluation.matchedPolicy().version());
            putIfPresent(metadata, "policyMatched", evaluation.matched());
            putIfPresent(metadata, "severity", evaluation.finalSeverity());
            putIfPresent(metadata, "workflowTemplate", evaluation.workflowTemplate());
            putIfPresent(metadata, "policyReason", evaluation.reason());
            putIfPresent(metadata, "policyRagFilters", evaluation.ragFilters());
        }
        if (activeState != null) {
            putIfPresent(metadata, "activeStatus", activeState.status());
            putIfPresent(metadata, "activeSeverity", activeState.severity());
            putIfPresent(metadata, "activeCount", activeState.count());
            putIfPresent(metadata, "firstSeen", activeState.firstSeen() == null ? null : activeState.firstSeen().toString());
            putIfPresent(metadata, "lastSeen", activeState.lastSeen() == null ? null : activeState.lastSeen().toString());
        }
        if (memoryRecall != null) {
            putIfPresent(metadata, "alertMemoryRecallCount", memoryRecall.entries().size());
            putIfPresent(metadata, "alertMemoryIds", memoryRecall.entries().stream().map(MemoryEntry::id).toList());
            putIfPresent(metadata, "alertMemoryWarning", memoryRecall.warning());
        }
        if (context != null) {
            putIfPresent(metadata, "degraded", context.isDegraded());
            putIfPresent(metadata, "failedNodes", context.getFailedNodes());
            putIfPresent(metadata, "skippedNodes", context.getSkippedNodes());
            putIfPresent(metadata, "alertMemoryExtractionWarning", context.getAttribute("alertMemoryExtractionWarning"));
            putIfPresent(metadata, "alertMemoryConsumed", context.getAttribute("alertMemoryConsumed"));
            putIfPresent(metadata, "alertMemoryConsumedIds", context.getAttribute("alertMemoryConsumedIds"));
            putIfPresent(metadata, "repeatIncident", context.getAttribute("repeatIncident"));
            putIfPresent(metadata, "silenceApproved", context.getAttribute("silenceApproved"));
            putIfPresent(metadata, "silenceApprovedBy", context.getAttribute("silenceApprovedBy"));
            putIfPresent(metadata, "silenceApprovalReason", context.getAttribute("silenceApprovalReason"));
            putIfPresent(metadata, "silenceApprovalExpiresAt", context.getAttribute("silenceApprovalExpiresAt"));
            putIfPresent(metadata, "silenceApprovalKey", context.getAttribute("silenceApprovalKey"));
            putIfPresent(metadata, "silenceApprovalWarning", context.getAttribute("silenceApprovalWarning"));
        }
        if (results != null && !results.isEmpty()) {
            putIfPresent(metadata, "nodeResultCount", results.size());
            putIfPresent(metadata, "nodeNames", results.stream().map(NodeResult::nodeName).toList());
            NodeResult latest = results.get(results.size() - 1);
            putIfPresent(metadata, "latestNode", latest.nodeName());
            putIfPresent(metadata, "latestNodeStatus", latest.status());
        }
        if (extras != null) {
            extras.forEach((key, value) -> putIfPresent(metadata, key, value));
        }
        return metadata;
    }

    private void appendPart(StringBuilder builder, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("; ");
        }
        builder.append(key).append('=').append(text);
    }

    private long consumedMemoryCount(AlertWorkflowContext context) {
        Object value = context.getAttribute("alertMemoryConsumed");
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        metadata.put(key, value);
    }

    private List<Map<String, Object>> memoryPayload(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", entry.id());
                    payload.put("type", entry.type() == null ? null : entry.type().name());
                    payload.put("scope", entry.scope() == null ? null : entry.scope().name());
                    payload.put("subject", entry.subject());
                    payload.put("service", entry.service());
                    payload.put("resource", entry.resource());
                    payload.put("fingerprint", entry.fingerprint());
                    payload.put("updatedAt", entry.updatedAt() == null ? null : entry.updatedAt().toString());
                    payload.put("content", entry.content());
                    return payload;
                })
                .toList();
    }

    private Map<String, Object> activeAlarmPayload(ActiveAlarmState activeState) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        if (activeState == null) {
            payload.put("present", false);
            return payload;
        }
        payload.put("present", true);
        payload.put("fingerprint", activeState.fingerprint());
        payload.put("status", activeState.status() == null ? null : activeState.status().name());
        payload.put("severity", activeState.severity() == null ? null : activeState.severity().name());
        payload.put("firstSeen", activeState.firstSeen() == null ? null : activeState.firstSeen().toString());
        payload.put("lastSeen", activeState.lastSeen() == null ? null : activeState.lastSeen().toString());
        payload.put("count", activeState.count());
        return payload;
    }

    private LinkedHashMap<String, Object> recoveryPayload(AlarmRecoveryState state) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", state.fingerprint());
        payload.put("status", state.status());
        payload.put("severity", state.severity() == null ? null : state.severity().name());
        payload.put("policyId", state.policyId());
        payload.put("recoverExpression", state.recoverExpression());
        payload.put("candidateAt", state.candidateAt().toString());
        payload.put("confirmAfter", state.confirmAfter().toString());
        payload.put("manualConfirmationRequired", state.manualConfirmationRequired());
        return payload;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private record SuppressionDecision(
            boolean suppressed,
            String nodeName,
            String suppressionKey,
            String reason
    ) {
        private static SuppressionDecision none() {
            return new SuppressionDecision(false, null, null, "");
        }
    }

    private record MemoryRecallResult(List<MemoryEntry> entries, String warning) {
        private MemoryRecallResult {
            entries = entries == null ? List.of() : entries;
            warning = warning == null ? "" : warning;
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

    private static NormalizedAlarmEvent withFingerprint(NormalizedAlarmEvent event, String fingerprint) {
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
