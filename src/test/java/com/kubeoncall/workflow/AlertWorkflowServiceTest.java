package com.kubeoncall.workflow;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.AlarmPolicyRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());

        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, executor, auditService, engine);

        AlarmEvent event = new AlarmEvent("alarm-1", "dedup-1", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of());
        List<NodeResult> results = service.process(event);

        assertEquals(1, results.size());
        assertEquals(NodeStatus.FAILURE, results.get(0).status());
        assertTrue(results.get(0).message().contains("Duplicate alarm ignored"));
        // Dedup key must be the fingerprint, never "alarm-dedup:null".
        Object dedupKey = results.get(0).payload().get("dedupKey");
        assertNotNull(dedupKey);
        assertTrue(((String) dedupKey).startsWith("dedup-1"));
        verify(factory, never()).buildWorkflow();
        verify(factory, never()).buildWorkflow(anyString());
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
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());

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

        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);
        AlarmEvent event = new AlarmEvent("alarm-2", "dedup-2", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of());

        List<NodeResult> results = service.process(event);

        assertEquals(2, results.size());
        assertEquals("upstreamNode", results.get(0).nodeName());
        assertEquals("dependentNode", results.get(1).nodeName());
        assertTrue(results.get(1).message().contains("Skipped due to unmet dependencies"));
        verify(auditService).recordAlarmExecution(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldUseFingerprintForDedupWhenNoDedupKeyProvided() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(valueOperations.setIfAbsent(keyCaptor.capture(), anyString(), any())).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow()).thenReturn(List.of());
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());

        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "alarm-3", null, "HostHighCpuUsage", "prometheus", "warning", AlarmSeverity.P2,
                null, "node-a", "lab-cluster", "monitoring", "infra-exporter",
                "host.cpu.usage_percent", 72.0, 70.0, "%", "10m",
                Map.of("team", "infra"), Map.of(), "runbook-host-cpu-high", null,
                Instant.now(), "cpu high", Map.of());

        service.process(event);

        String dedupKey = keyCaptor.getValue();
        assertTrue(dedupKey.startsWith("alarm-dedup:fp:"), "dedup key should be a generated fingerprint, not alarm-dedup:null; got " + dedupKey);
    }

    @Test
    void shouldEvaluatePolicyAndCarryResultOnContext() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(cpuRepository());

        AlertWorkflowDefinition capturing = new AlertWorkflowDefinition(
                "capturingNode",
                true,
                List.of(),
                context -> {
                    AlarmEvaluationResult result = context.getEvaluationResult();
                    assertNotNull(result, "policy evaluation result must be on the context");
                    assertTrue(result.matched());
                    assertEquals(AlarmSeverity.P1, result.finalSeverity());
                    assertEquals("host-high-cpu-p1", result.policyId());
                    return new NodeResult("capturingNode", NodeStatus.SUCCESS, "ok", Map.of());
                }
        );
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow("host-resource")).thenReturn(List.of(capturing));

        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        NormalizedAlarmEvent event = cpuEvent(70.1);
        List<NodeResult> results = service.process(event);

        assertEquals(1, results.size());
        assertEquals(NodeStatus.SUCCESS, results.get(0).status());
    }

    @Test
    void shouldConfirmRecoveryWithoutRunningWorkflowOrDedup() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-recovered", "fp-recovered", "HostHighCpuUsageP1", "prometheus", "resolved", AlarmSeverity.INFO,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE, "node-a", "cluster-a", "monitoring", "infra",
                "host.cpu.usage_percent", 30.0, 70.0, "%", "10m",
                Map.of("team", "infra"), Map.of(), "runbook-host-cpu-high", AlarmStatus.RESOLVED,
                Instant.now(), "cpu recovered", Map.of()));

        assertEquals(1, results.size());
        assertEquals("alarmRecovery", results.get(0).nodeName());
        assertEquals(NodeStatus.SUCCESS, results.get(0).status());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
        verify(factory, never()).buildWorkflow();
        verify(factory, never()).buildWorkflow(anyString());
        verify(auditService).recordAlarmExecution(anyString(), org.mockito.ArgumentMatchers.eq("RECOVERED"), anyBoolean(), anyBoolean(), anyString(), any(), any(), any());
    }

    private static NormalizedAlarmEvent cpuEvent(double currentValue) {
        return new NormalizedAlarmEvent(
                "alarm-cpu", "fp-cpu", "HostHighCpuUsageP1", "prometheus", "warning", null,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE, "node-a", "lab-cluster", "monitoring", "infra-exporter",
                "host.cpu.usage_percent", currentValue, 70.0, "%", "10m",
                Map.of("team", "infra"), Map.of(), "runbook-host-cpu-high", null,
                Instant.now(), "cpu high", Map.of());
    }

    private static AlarmPolicyRepository emptyRepository() {
        return new AlarmPolicyRepository() {
            @Override
            public List<AlarmPolicy> findAll() {
                return List.of();
            }

            @Override
            public Optional<AlarmPolicy> findById(String policyId) {
                return Optional.empty();
            }

            @Override
            public Optional<AlarmPolicy> findByName(String name) {
                return Optional.empty();
            }
        };
    }

    private static AlarmPolicyRepository cpuRepository() {
        com.kubeoncall.alarm.domain.AlarmCondition condition = new com.kubeoncall.alarm.domain.AlarmCondition(
                "HostHighCpuUsageP1", "host.cpu.usage_percent", com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                ">", 70.0, "10m", Map.of());
        com.kubeoncall.alarm.domain.AlarmAction action = new com.kubeoncall.alarm.domain.AlarmAction(
                "host-resource", "oncall", false, false, List.of());
        AlarmPolicy p1 = new AlarmPolicy("host-high-cpu-p1", "HostHighCpuUsageP1", "host",
                "host.cpu.usage_percent", com.kubeoncall.alarm.domain.AlarmResourceType.NODE, AlarmSeverity.P1,
                condition, "cpu > 70", "15m", "cpu < 65", "runbook-host-cpu-high", "infra", action, Map.of("team", "infra"));
        return new AlarmPolicyRepository() {
            @Override
            public List<AlarmPolicy> findAll() {
                return List.of(p1);
            }

            @Override
            public Optional<AlarmPolicy> findById(String policyId) {
                return findById0(policyId);
            }

            private Optional<AlarmPolicy> findById0(String policyId) {
                return "host-high-cpu-p1".equals(policyId) ? Optional.of(p1) : Optional.empty();
            }

            @Override
            public Optional<AlarmPolicy> findByName(String name) {
                return "HostHighCpuUsageP1".equals(name) ? Optional.of(p1) : Optional.empty();
            }
        };
    }
}
