package com.kubeoncall.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
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
    private final AlarmFingerprintService alarmFingerprintService = new AlarmFingerprintService();

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine) {
        this(redisTemplate, properties, alertWorkflowFactory, workflowNodeExecutor, executionAuditService,
                alarmPolicyEngine, new ActiveAlarmStore(redisTemplate, new ObjectMapper(), properties));
    }

    @Autowired
    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService,
                                AlarmPolicyEngine alarmPolicyEngine,
                                ActiveAlarmStore activeAlarmStore) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.alertWorkflowFactory = alertWorkflowFactory;
        this.workflowNodeExecutor = workflowNodeExecutor;
        this.executionAuditService = executionAuditService;
        this.alarmPolicyEngine = alarmPolicyEngine;
        this.activeAlarmStore = activeAlarmStore;
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
        if (event.status() == AlarmStatus.RESOLVED) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("fingerprint", fingerprint);
            payload.put("activeAlarm", activeAlarmPayload(activeState));
            NodeResult result = new NodeResult(
                    "alarmRecovery",
                    NodeStatus.SUCCESS,
                    "Alarm recovery confirmed",
                    payload
            );
            executionAuditService.recordAlarmExecution(
                    event.alarmId() == null ? fingerprint : event.alarmId(),
                    "RECOVERED",
                    true,
                    false,
                    result.message(),
                    null,
                    List.of("alarm.recovery"),
                    startedAt
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
                    startedAt
            );
            return List.of(result);
        }

        AlarmEvent legacy = toLegacy(event, evaluation);

        AlertWorkflowContext context = new AlertWorkflowContext(legacy, event, evaluation, startedAt);
        context.putAttribute("activeAlarm", activeState);
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
        if (toolNames.isEmpty()) {
            toolNames.addAll(results.stream().map(NodeResult::nodeName).toList());
        }
        executionAuditService.recordAlarmExecution(
                event.alarmId() == null ? fingerprint : event.alarmId(),
                status,
                noIssues,
                false,
                latest.message(),
                failureReason,
                new ArrayList<>(toolNames),
                startedAt
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

    private Map<String, Object> activeAlarmPayload(ActiveAlarmState activeState) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("fingerprint", activeState.fingerprint());
        payload.put("status", activeState.status() == null ? null : activeState.status().name());
        payload.put("severity", activeState.severity() == null ? null : activeState.severity().name());
        payload.put("firstSeen", activeState.firstSeen() == null ? null : activeState.firstSeen().toString());
        payload.put("lastSeen", activeState.lastSeen() == null ? null : activeState.lastSeen().toString());
        payload.put("count", activeState.count());
        return payload;
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
