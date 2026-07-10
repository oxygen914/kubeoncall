package com.kubeoncall.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.AlarmPolicyRepository;
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
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.workflow.node.IntelligentDiagnosisNode;
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
import static org.mockito.ArgumentMatchers.eq;
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
        verify(auditService).recordAlarmExecution(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyMap());
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
        verify(auditService).recordAlarmExecution(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), any(), any(), org.mockito.ArgumentMatchers.anyMap());
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
    void shouldAttachAlertMemoryToWorkflowContextAndExtractAfterWorkflow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        AlertMemoryService alertMemoryService = mock(AlertMemoryService.class);
        MemoryExtractor memoryExtractor = mock(MemoryExtractor.class);
        MemoryEntry memory = new MemoryEntry(
                "memory-1",
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.FINGERPRINT,
                "previous oom",
                "scaled payment-service after OOM",
                "payment-service",
                "payment-pod",
                "fp-memory",
                Instant.now(),
                Instant.now(),
                Map.of()
        );
        when(alertMemoryService.recall(any(NormalizedAlarmEvent.class))).thenReturn(List.of(memory));

        IntelligentDiagnosisNode diagnosisNode = new IntelligentDiagnosisNode(properties, new TokenBudget());
        AlertWorkflowDefinition diagnosis = new AlertWorkflowDefinition(
                "intelligentDiagnosisNode", false, List.of(), diagnosisNode);
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow()).thenReturn(List.of(diagnosis));
        AlertWorkflowService service = new AlertWorkflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                new ActiveAlarmStore(redisTemplate, new ObjectMapper(), properties),
                alertMemoryService,
                memoryExtractor
        );

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-memory", "fp-memory", "PodOOMKilled", "prometheus", "warning", AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD, "payment-pod", "cluster-a", "prod", "payment-service",
                "container_memory_working_set_bytes", 2.0, 1.0, "GiB", "5m",
                Map.of(), Map.of(), "runbook-oom", null, Instant.now(), "oom", Map.of()));

        assertEquals(1, results.size());
        assertEquals("intelligentDiagnosisNode", results.get(0).nodeName());
        assertEquals(true, results.get(0).payload().get("memoryConsumed"));
        assertEquals(1, results.get(0).payload().get("memoryConsumedCount"));
        verify(alertMemoryService).recall(any(NormalizedAlarmEvent.class));
        org.mockito.ArgumentCaptor<String> summaryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(memoryExtractor).extractFromAlarm(any(NormalizedAlarmEvent.class), summaryCaptor.capture());
        assertTrue(summaryCaptor.getValue().contains("fingerprint=fp-memory"));
        assertTrue(summaryCaptor.getValue().contains("memoryRecallCount=1"));
        assertTrue(summaryCaptor.getValue().contains("memoryConsumedCount=1"));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordAlarmExecution(
                anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), any(), any(), any(), metadataCaptor.capture());
        assertEquals(1, metadataCaptor.getValue().get("alertMemoryConsumed"));
        assertEquals(List.of("memory-1"), metadataCaptor.getValue().get("alertMemoryConsumedIds"));
        assertEquals(true, metadataCaptor.getValue().get("repeatIncident"));
    }

    @Test
    void shouldAttachSilenceApprovalToWorkflowContextAndAuditMetadata() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        ActiveAlarmStore activeAlarmStore = mock(ActiveAlarmStore.class);
        when(activeAlarmStore.record(any(NormalizedAlarmEvent.class), any(AlarmEvaluationResult.class), eq("fp-silence")))
                .thenReturn(new ActiveAlarmState(
                        "fp-silence", "alarm-silence", "PodCrashLoop", "cluster-a", "prod", "payment-service", "payment-pod",
                        AlarmSeverity.P1, AlarmStatus.FIRING, null,
                        Instant.now().minusSeconds(60), Instant.now(), 1));
        Instant expiresAt = Instant.now().plusSeconds(600);
        AlarmSilenceApprovalStore silenceApprovalStore = mock(AlarmSilenceApprovalStore.class);
        when(silenceApprovalStore.find("fp-silence")).thenReturn(Optional.of(
                new AlarmSilenceApprovalStore.SilenceApproval(
                        "fp-silence",
                        "incident-commander",
                        "approved maintenance silence",
                        Instant.now(),
                        expiresAt
                )));
        when(silenceApprovalStore.keyFor("fp-silence")).thenReturn("alarm-silence-approval:fp-silence");

        AlertWorkflowDefinition capturing = new AlertWorkflowDefinition(
                "capturingNode",
                true,
                List.of(),
                context -> {
                    assertEquals(true, context.getAttribute("silenceApproved"));
                    assertEquals("incident-commander", context.getAttribute("silenceApprovedBy"));
                    assertEquals("approved maintenance silence", context.getAttribute("silenceApprovalReason"));
                    assertEquals(expiresAt.toString(), context.getAttribute("silenceApprovalExpiresAt"));
                    return new NodeResult("capturingNode", NodeStatus.SUCCESS, "ok", Map.of());
                }
        );
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow()).thenReturn(List.of(capturing));

        AlertWorkflowService service = new AlertWorkflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop(),
                silenceApprovalStore
        );

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-silence", "fp-silence", "PodCrashLoop", "prometheus", "warning", AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD, "payment-pod", "cluster-a", "prod", "payment-service",
                "kube_pod_container_status_restarts_total", 4.0, 3.0, "count", "5m",
                Map.of(), Map.of(), "runbook-pod", null, Instant.now(), "pod crash", Map.of()));

        assertEquals(1, results.size());
        verify(silenceApprovalStore).find("fp-silence");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordAlarmExecution(anyString(), anyString(), anyBoolean(), anyBoolean(), anyString(), any(), any(), any(), metadataCaptor.capture());
        assertEquals(true, metadataCaptor.getValue().get("silenceApproved"));
        assertEquals("incident-commander", metadataCaptor.getValue().get("silenceApprovedBy"));
        assertEquals("approved maintenance silence", metadataCaptor.getValue().get("silenceApprovalReason"));
        assertEquals("alarm-silence-approval:fp-silence", metadataCaptor.getValue().get("silenceApprovalKey"));
    }

    @Test
    void shouldSuppressPodNoiseWhenNodeNotReadyIsActiveOnSameNode() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey("alarm-suppression:node:cluster-a:node-a")).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        AlertWorkflowService service = new AlertWorkflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-pod", "fp-pod", "PodCrashLoop", "prometheus", "warning", AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD, "payment-pod", "cluster-a", "prod", "payment-service",
                "kube_pod_container_status_restarts_total", 5.0, 3.0, "count", "5m",
                Map.of("node", "node-a"), Map.of(), "runbook-pod", null, Instant.now(), "pod crash", Map.of()));

        assertEquals(1, results.size());
        assertEquals("alarmSuppressed", results.get(0).nodeName());
        assertEquals(NodeStatus.SUCCESS, results.get(0).status());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
        verify(factory, never()).buildWorkflow();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordAlarmExecution(anyString(), eq("SUPPRESSED"), anyBoolean(), anyBoolean(), anyString(), any(), any(), any(), metadataCaptor.capture());
        assertEquals("fp-pod", metadataCaptor.getValue().get("fingerprint"));
        assertEquals(true, metadataCaptor.getValue().get("suppressed"));
    }

    @Test
    void shouldEscalateUnacknowledgedP0AlarmAfterConfiguredRepeatCount() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(redisTemplate.hasKey("alarm-ack:fp-p0")).thenReturn(false);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setP0EscalationCount(2);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(p0Repository());
        ActiveAlarmStore activeAlarmStore = mock(ActiveAlarmStore.class);
        when(activeAlarmStore.record(any(NormalizedAlarmEvent.class), any(AlarmEvaluationResult.class), eq("fp-p0")))
                .thenReturn(new com.kubeoncall.alarm.state.ActiveAlarmState(
                        "fp-p0", "alarm-p0", "HostHighCpuUsageP0", "cluster-a", "prod", "infra", "node-a",
                        AlarmSeverity.P0, AlarmStatus.FIRING, "host-high-cpu-p0",
                        Instant.now().minusSeconds(600), Instant.now(), 2));
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow("host-resource")).thenReturn(List.of());
        AlertWorkflowService service = new AlertWorkflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop()
        );

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-p0", "fp-p0", "HostHighCpuUsageP0", "prometheus", "critical", null,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE, "node-a", "cluster-a", "prod", "infra",
                "host.cpu.usage_percent", 91.0, 85.0, "%", "10m",
                Map.of(), Map.of(), "runbook-host-cpu-high", null, Instant.now(), "cpu very high", Map.of()));

        assertTrue(results.stream().anyMatch(result -> "alarmEscalation".equals(result.nodeName())));
        verify(valueOperations).setIfAbsent(eq("alarm-escalation:fp-p0"), eq("P0"), any());
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
        verify(auditService).recordAlarmExecution(anyString(), org.mockito.ArgumentMatchers.eq("RECOVERED"), anyBoolean(), anyBoolean(), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyMap());
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

    private static AlarmPolicyRepository p0Repository() {
        com.kubeoncall.alarm.domain.AlarmCondition condition = new com.kubeoncall.alarm.domain.AlarmCondition(
                "HostHighCpuUsageP0", "host.cpu.usage_percent", com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                ">", 85.0, "10m", Map.of());
        com.kubeoncall.alarm.domain.AlarmAction action = new com.kubeoncall.alarm.domain.AlarmAction(
                "host-resource", "oncall", false, false, List.of());
        AlarmPolicy p0 = new AlarmPolicy("host-high-cpu-p0", "HostHighCpuUsageP0", "host",
                "host.cpu.usage_percent", com.kubeoncall.alarm.domain.AlarmResourceType.NODE, AlarmSeverity.P0,
                condition, "cpu > 85", "15m", "cpu < 75", "runbook-host-cpu-high", "infra", action, Map.of("team", "infra"));
        return new AlarmPolicyRepository() {
            @Override
            public List<AlarmPolicy> findAll() {
                return List.of(p0);
            }

            @Override
            public Optional<AlarmPolicy> findById(String policyId) {
                return "host-high-cpu-p0".equals(policyId) ? Optional.of(p0) : Optional.empty();
            }

            @Override
            public Optional<AlarmPolicy> findByName(String name) {
                return "HostHighCpuUsageP0".equals(name) ? Optional.of(p0) : Optional.empty();
            }
        };
    }
}
