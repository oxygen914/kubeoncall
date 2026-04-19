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
                    Map.of("dedupKey", alarmEvent.dedupKey())
            );
            executionAuditService.recordAlarmExecution(
                    alarmEvent.alarmId(),
                    "FAILED",
                    false,
                    false,
                    result.message(),
                    result.message(),
                    List.of("alarm.dedup"),
                    startedAt
            );
            return List.of(result);
        }

        AlertWorkflowContext context = new AlertWorkflowContext(alarmEvent, startedAt);
        Duration nodeTimeout = Duration.ofMillis(properties.getWorkflow().getNodeTimeoutMillis());
        for (AlertWorkflowDefinition definition : alertWorkflowFactory.buildWorkflow()) {
            workflowNodeExecutor.execute(definition, context, nodeTimeout);
            if (context.isTerminated()) {
                break;
            }
        }

        List<NodeResult> results = context.getNodeResults();
        NodeResult latest = results.get(results.size() - 1);
        String status = context.getFailedNodes().isEmpty() ? "SUCCESS" : "DEGRADED";
        String failureReason = context.getFailedNodes().isEmpty()
                ? null
                : "Failed nodes: " + String.join(", ", context.getFailedNodes());
        executionAuditService.recordAlarmExecution(
                alarmEvent.alarmId(),
                status,
                context.getFailedNodes().isEmpty(),
                false,
                latest.message(),
                failureReason,
                results.stream().map(NodeResult::nodeName).toList(),
                startedAt
        );
        return results;
    }
}
