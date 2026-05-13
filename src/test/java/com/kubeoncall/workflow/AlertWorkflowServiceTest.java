package com.kubeoncall.workflow;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.service.ExecutionAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertWorkflowServiceTest {

    @Test
    void shouldReturnDedupFailureWhenAlarmAlreadyProcessed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);

        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, executor, auditService);

        AlarmEvent event = new AlarmEvent("alarm-1", "dedup-1", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of());
        List<NodeResult> results = service.process(event);

        assertEquals(1, results.size());
        assertEquals(NodeStatus.FAILURE, results.get(0).status());
        assertTrue(results.get(0).message().contains("Duplicate alarm ignored"));
        verify(factory, never()).buildWorkflow();
        verify(auditService).recordAlarmExecution(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), any(), any(), any());
    }

    @Test
    void shouldSkipNodeWhenDependenciesUnmet() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);

        AlertWorkflowDefinition upstream = new AlertWorkflowDefinition(
                "upstreamNode",
                true,
                List.of(),
                context -> new NodeResult("upstreamNode", NodeStatus.FAILURE, "failed", Map.of())
        );
        AlertWorkflowDefinition dependent = new AlertWorkflowDefinition(
                "dependentNode",
                true,
                List.of("upstreamNode"),
                context -> new NodeResult("dependentNode", NodeStatus.SUCCESS, "ok", Map.of())
        );
        when(factory.buildWorkflow()).thenReturn(List.of(upstream, dependent));

        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService);
        AlarmEvent event = new AlarmEvent("alarm-2", "dedup-2", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of());

        List<NodeResult> results = service.process(event);

        assertEquals(2, results.size());
        assertEquals("upstreamNode", results.get(0).nodeName());
        assertEquals("dependentNode", results.get(1).nodeName());
        assertTrue(results.get(1).message().contains("Skipped due to unmet dependencies"));
        verify(auditService).recordAlarmExecution(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), any(), any());
    }
}
