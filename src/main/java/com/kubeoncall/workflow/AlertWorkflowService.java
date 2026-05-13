package com.kubeoncall.workflow;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.service.ExecutionAuditService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

    public AlertWorkflowService(StringRedisTemplate redisTemplate,
                                KubeOnCallProperties properties,
                                AlertWorkflowFactory alertWorkflowFactory,
                                WorkflowNodeExecutor workflowNodeExecutor,
                                ExecutionAuditService executionAuditService) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.alertWorkflowFactory = alertWorkflowFactory;
        this.workflowNodeExecutor = workflowNodeExecutor;
        this.executionAuditService = executionAuditService;
    }

    public List<NodeResult> process(AlarmEvent alarmEvent) {
        Instant startedAt = Instant.now();
        String dedupKey = "alarm-dedup:" + alarmEvent.dedupKey();
        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                dedupKey,
                alarmEvent.alarmId(),
                Duration.ofSeconds(properties.getWorkflow().getAlarmDedupTtlSeconds())
        );
        if (Boolean.FALSE.equals(accepted)) {
            NodeResult result = new NodeResult(
                    "alarmDedup",
                    NodeStatus.FAILURE,
                    "Duplicate alarm ignored",
                    Map.of("dedupKey", alarmEvent.dedupKey(), "dedupHit", true)
            );
            executionAuditService.recordAlarmExecution(
                    alarmEvent.alarmId(),
                    "DEDUP_HIT",
                    true,
                    false,
                    "Duplicate alarm ignored: dedupKey=" + alarmEvent.dedupKey(),
                    null,
                    List.of("alarm.dedup", "alarm.dedup.hit"),
                    startedAt
            );
            return List.of(result);
        }

        AlertWorkflowContext context = new AlertWorkflowContext(alarmEvent, startedAt);
        Duration nodeTimeout = Duration.ofMillis(properties.getWorkflow().getNodeTimeoutMillis());
        for (AlertWorkflowDefinition definition : alertWorkflowFactory.buildWorkflow()) {
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
                alarmEvent.alarmId(),
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
}
