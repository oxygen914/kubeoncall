package com.kubeoncall.workflow;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.escalation.AlarmEscalationService;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindow;
import com.kubeoncall.alarm.maintenance.AlarmMaintenanceWindowService;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.AlarmPolicyRepository;
import com.kubeoncall.alarm.recovery.AlarmRecoveryService;
import com.kubeoncall.alarm.recovery.AlarmRecoveryState;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.alarm.state.AlarmSilenceApprovalStore;
import com.kubeoncall.alarm.suppression.AlarmSuppressionService;
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
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillActivationService;
import com.kubeoncall.workflow.node.IntelligentDiagnosisNode;

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

        AlertWorkflowService service =
                workflowService(redisTemplate, properties, factory, executor, auditService, engine);

        AlarmEvent event =
                new AlarmEvent("alarm-1", "dedup-1", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of());
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
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyMap());
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
                context -> new NodeResult("upstreamNode", NodeStatus.FAILURE, "failed", Map.of()));
        AlertWorkflowDefinition dependent = new AlertWorkflowDefinition(
                "dependentNode",
                true,
                List.of("upstreamNode"),
                context -> new NodeResult("dependentNode", NodeStatus.SUCCESS, "ok", Map.of()));
        when(factory.buildWorkflow()).thenReturn(List.of(upstream, dependent));

        AlertWorkflowService service =
                workflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);
        AlarmEvent event =
                new AlarmEvent("alarm-2", "dedup-2", "prom", "critical", "node-a", "cpu high", Instant.now(), Map.of());

        List<NodeResult> results = service.process(event);

        assertEquals(2, results.size());
        assertEquals("upstreamNode", results.get(0).nodeName());
        assertEquals("dependentNode", results.get(1).nodeName());
        assertTrue(results.get(1).message().contains("Skipped due to unmet dependencies"));
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void shouldUseFingerprintForDedupWhenNoDedupKeyProvided() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(valueOperations.setIfAbsent(keyCaptor.capture(), anyString(), any()))
                .thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow()).thenReturn(List.of());
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());

        AlertWorkflowService service =
                workflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "alarm-3",
                null,
                "HostHighCpuUsage",
                "prometheus",
                "warning",
                AlarmSeverity.P2,
                null,
                "node-a",
                "lab-cluster",
                "monitoring",
                "infra-exporter",
                "host.cpu.usage_percent",
                72.0,
                70.0,
                "%",
                "10m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-host-cpu-high",
                null,
                Instant.now(),
                "cpu high",
                Map.of());

        service.process(event);

        String dedupKey = keyCaptor.getValue();
        assertTrue(
                dedupKey.startsWith("alarm-dedup:fp:"),
                "dedup key should be a generated fingerprint, not alarm-dedup:null; got " + dedupKey);
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

        AlertWorkflowDefinition capturing = new AlertWorkflowDefinition("capturingNode", true, List.of(), context -> {
            AlarmEvaluationResult result = context.getEvaluationResult();
            assertNotNull(result, "policy evaluation result must be on the context");
            assertTrue(result.matched());
            assertEquals(AlarmSeverity.P1, result.finalSeverity());
            assertEquals("host-high-cpu-p1", result.policyId());
            return new NodeResult("capturingNode", NodeStatus.SUCCESS, "ok", Map.of());
        });
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow("host-resource")).thenReturn(List.of(capturing));

        AlertWorkflowService service =
                workflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

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
                Map.of());
        when(alertMemoryService.recall(any(NormalizedAlarmEvent.class))).thenReturn(List.of(memory));

        SkillActivationService skillActivationService = mock(SkillActivationService.class);
        when(skillActivationService.activate(anyString(), any())).thenReturn(SkillActivation.empty());
        IntelligentDiagnosisNode diagnosisNode =
                new IntelligentDiagnosisNode(properties, new TokenBudget(), skillActivationService);
        AlertWorkflowDefinition diagnosis =
                new AlertWorkflowDefinition("intelligentDiagnosisNode", false, List.of(), diagnosisNode);
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow()).thenReturn(List.of(diagnosis));
        AlertWorkflowService service = workflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                new ActiveAlarmStore(redisTemplate, new ObjectMapper(), properties),
                alertMemoryService,
                memoryExtractor);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-memory",
                "fp-memory",
                "PodOOMKilled",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-service",
                "container_memory_working_set_bytes",
                2.0,
                1.0,
                "GiB",
                "5m",
                Map.of(),
                Map.of(),
                "runbook-oom",
                null,
                Instant.now(),
                "oom",
                Map.of()));

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
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        metadataCaptor.capture());
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
        when(activeAlarmStore.record(
                        any(NormalizedAlarmEvent.class), any(AlarmEvaluationResult.class), eq("fp-silence")))
                .thenReturn(new ActiveAlarmState(
                        "fp-silence",
                        "alarm-silence",
                        "PodCrashLoop",
                        "cluster-a",
                        "prod",
                        "payment-service",
                        "payment-pod",
                        AlarmSeverity.P1,
                        AlarmStatus.FIRING,
                        null,
                        Instant.now().minusSeconds(60),
                        Instant.now(),
                        1));
        Instant expiresAt = Instant.now().plusSeconds(600);
        AlarmSilenceApprovalStore silenceApprovalStore = mock(AlarmSilenceApprovalStore.class);
        when(silenceApprovalStore.find("fp-silence"))
                .thenReturn(Optional.of(new AlarmSilenceApprovalStore.SilenceApproval(
                        "fp-silence", "incident-commander", "approved maintenance silence", Instant.now(), expiresAt)));
        when(silenceApprovalStore.keyFor("fp-silence")).thenReturn("alarm-silence-approval:fp-silence");

        AlertWorkflowDefinition capturing = new AlertWorkflowDefinition("capturingNode", true, List.of(), context -> {
            assertEquals(true, context.getAttribute("silenceApproved"));
            assertEquals("incident-commander", context.getAttribute("silenceApprovedBy"));
            assertEquals("approved maintenance silence", context.getAttribute("silenceApprovalReason"));
            assertEquals(expiresAt.toString(), context.getAttribute("silenceApprovalExpiresAt"));
            return new NodeResult("capturingNode", NodeStatus.SUCCESS, "ok", Map.of());
        });
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow()).thenReturn(List.of(capturing));

        AlertWorkflowService service = workflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop(),
                silenceApprovalStore);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-silence",
                "fp-silence",
                "PodCrashLoop",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-service",
                "kube_pod_container_status_restarts_total",
                4.0,
                3.0,
                "count",
                "5m",
                Map.of(),
                Map.of(),
                "runbook-pod",
                null,
                Instant.now(),
                "pod crash",
                Map.of()));

        assertEquals(1, results.size());
        verify(silenceApprovalStore).find("fp-silence");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        anyString(),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        metadataCaptor.capture());
        assertEquals(true, metadataCaptor.getValue().get("silenceApproved"));
        assertEquals("incident-commander", metadataCaptor.getValue().get("silenceApprovedBy"));
        assertEquals("approved maintenance silence", metadataCaptor.getValue().get("silenceApprovalReason"));
        assertEquals(
                "alarm-silence-approval:fp-silence", metadataCaptor.getValue().get("silenceApprovalKey"));
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
        AlertWorkflowService service =
                workflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-pod",
                "fp-pod",
                "PodCrashLoop",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-service",
                "kube_pod_container_status_restarts_total",
                5.0,
                3.0,
                "count",
                "5m",
                Map.of("node", "node-a"),
                Map.of(),
                "runbook-pod",
                null,
                Instant.now(),
                "pod crash",
                Map.of()));

        assertEquals(1, results.size());
        assertEquals("alarmSuppressed", results.get(0).nodeName());
        assertEquals(NodeStatus.SUCCESS, results.get(0).status());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
        verify(factory, never()).buildWorkflow();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        eq("SUPPRESSED"),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        metadataCaptor.capture());
        assertEquals("fp-pod", metadataCaptor.getValue().get("fingerprint"));
        assertEquals(true, metadataCaptor.getValue().get("suppressed"));
    }

    @Test
    void shouldSuppressAlarmInsideApprovedMaintenanceWindow() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        ActiveAlarmStore activeAlarmStore = mock(ActiveAlarmStore.class);
        when(activeAlarmStore.record(any(), any(), eq("fp-maintenance"))).thenReturn(null);
        AlarmMaintenanceWindowService maintenanceService = mock(AlarmMaintenanceWindowService.class);
        Instant now = Instant.now();
        AlarmMaintenanceWindow window = new AlarmMaintenanceWindow(
                "mw-1",
                now.minusSeconds(60),
                now.plusSeconds(600),
                Map.of("service", "payment-*"),
                "payment release",
                "operator-a",
                "approver-b",
                "change-123",
                now.minusSeconds(120));
        when(maintenanceService.matchingWindow(any(NormalizedAlarmEvent.class), any(Instant.class)))
                .thenReturn(Optional.of(window));
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        AlertWorkflowService service = workflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop(),
                null,
                null,
                null,
                maintenanceService);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-maintenance",
                "fp-maintenance",
                "PodCrashLoop",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-api",
                "restart_count",
                5.0,
                3.0,
                "count",
                "5m",
                Map.of("node", "node-a"),
                Map.of(),
                "runbook-pod",
                AlarmStatus.FIRING,
                now,
                "pod crash",
                Map.of()));

        assertEquals(1, results.size());
        assertEquals("alarmMaintenanceSuppressed", results.get(0).nodeName());
        assertEquals("mw-1", results.get(0).payload().get("maintenanceWindowId"));
        verify(factory, never()).buildWorkflow();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> metadataCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        eq("SUPPRESSED"),
                        eq(true),
                        eq(true),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        metadataCaptor.capture());
        assertEquals("maintenance_window", metadataCaptor.getValue().get("suppressedBy"));
        assertEquals("mw-1", metadataCaptor.getValue().get("maintenanceWindowId"));
    }

    @Test
    void shouldUseConfiguredSuppressionRuleAndExposeRootCause() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        ActiveAlarmStore activeAlarmStore = mock(ActiveAlarmStore.class);
        when(activeAlarmStore.record(any(), any(), eq("fp-derived"))).thenReturn(null);
        AlarmSuppressionService suppressionService = mock(AlarmSuppressionService.class);
        when(suppressionService.evaluate(any()))
                .thenReturn(new AlarmSuppressionService.SuppressionDecision(
                        true,
                        "node-not-ready-suppresses-pod",
                        "v1",
                        "alarm-suppression:rule:node-not-ready-suppresses-pod:cluster-a:node-a",
                        "fp-node-root",
                        "root cause active"));
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        AlertWorkflowService service = workflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop(),
                null,
                null,
                null,
                null,
                suppressionService);
        Instant now = Instant.now();

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-derived",
                "fp-derived",
                "PodCrashLoop",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                com.kubeoncall.alarm.domain.AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-api",
                "restart_count",
                5.0,
                3.0,
                "count",
                "5m",
                Map.of("node", "node-a"),
                Map.of(),
                "runbook-pod",
                AlarmStatus.FIRING,
                now,
                "pod crash",
                Map.of()));

        verify(suppressionService).recordSources(any());
        assertEquals(1, results.size());
        assertEquals("alarmSuppressed", results.get(0).nodeName());
        assertEquals("node-not-ready-suppresses-pod", results.get(0).payload().get("ruleId"));
        assertEquals("fp-node-root", results.get(0).payload().get("sourceFingerprint"));
        verify(factory, never()).buildWorkflow();
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
                        "fp-p0",
                        "alarm-p0",
                        "HostHighCpuUsageP0",
                        "cluster-a",
                        "prod",
                        "infra",
                        "node-a",
                        AlarmSeverity.P0,
                        AlarmStatus.FIRING,
                        "host-high-cpu-p0",
                        Instant.now().minusSeconds(600),
                        Instant.now(),
                        2));
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        when(factory.buildWorkflow("host-resource")).thenReturn(List.of());
        AlarmEscalationService escalationService = mock(AlarmEscalationService.class);
        when(escalationService.escalate(
                        any(NormalizedAlarmEvent.class), any(AlarmEvaluationResult.class), eq(2L), eq(2L)))
                .thenReturn(new NodeResult(
                        "alarmEscalation", NodeStatus.SUCCESS, "delivered", Map.of("deliveryStatus", "delivered")));
        AlertWorkflowService service = workflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop(),
                null,
                null,
                escalationService);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-p0",
                "fp-p0",
                "HostHighCpuUsageP0",
                "prometheus",
                "critical",
                null,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                "node-a",
                "cluster-a",
                "prod",
                "infra",
                "host.cpu.usage_percent",
                91.0,
                85.0,
                "%",
                "10m",
                Map.of(),
                Map.of(),
                "runbook-host-cpu-high",
                null,
                Instant.now(),
                "cpu very high",
                Map.of()));

        assertTrue(results.stream().anyMatch(result -> "alarmEscalation".equals(result.nodeName())));
        verify(valueOperations).setIfAbsent(eq("alarm-escalation:fp-p0"), eq("P0"), any());
        verify(escalationService)
                .escalate(any(NormalizedAlarmEvent.class), any(AlarmEvaluationResult.class), eq(2L), eq(2L));
    }

    @Test
    void shouldCreateRecoveryCandidateWhenResolvedAlarmIsReceived() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        AlertWorkflowService service =
                workflowService(redisTemplate, properties, factory, new WorkflowNodeExecutor(), auditService, engine);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-recovered",
                "fp-recovered",
                "HostHighCpuUsageP1",
                "prometheus",
                "resolved",
                AlarmSeverity.INFO,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                "node-a",
                "cluster-a",
                "monitoring",
                "infra",
                "host.cpu.usage_percent",
                30.0,
                70.0,
                "%",
                "10m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-host-cpu-high",
                AlarmStatus.RESOLVED,
                Instant.now(),
                "cpu recovered",
                Map.of()));

        assertEquals(1, results.size());
        assertEquals("alarmRecoveryPending", results.get(0).nodeName());
        assertEquals(NodeStatus.SUCCESS, results.get(0).status());
        assertEquals("PENDING", results.get(0).payload().get("status"));
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any());
        verify(factory, never()).buildWorkflow();
        verify(factory, never()).buildWorkflow(anyString());
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        org.mockito.ArgumentMatchers.eq("RECOVERY_PENDING"),
                        anyBoolean(),
                        anyBoolean(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void shouldCreateRecoveryCandidateInsteadOfImmediatelyConfirmingResolvedAlarm() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(emptyRepository());
        ActiveAlarmStore activeAlarmStore = mock(ActiveAlarmStore.class);
        ActiveAlarmState activeState = new ActiveAlarmState(
                "fp-pending",
                "alarm-pending",
                "HostHighCpuUsageP1",
                "cluster-a",
                "prod",
                "infra",
                "node-a",
                AlarmSeverity.P1,
                AlarmStatus.RESOLVED,
                "host-high-cpu-p1",
                Instant.now().minusSeconds(900),
                Instant.now().minusSeconds(1),
                3);
        when(activeAlarmStore.record(
                        any(NormalizedAlarmEvent.class), any(AlarmEvaluationResult.class), eq("fp-pending")))
                .thenReturn(activeState);
        AlarmRecoveryService recoveryService = mock(AlarmRecoveryService.class);
        Instant candidateAt = Instant.now();
        AlarmRecoveryState recoveryState = new AlarmRecoveryState(
                "fp-pending",
                "alarm-pending",
                AlarmSeverity.P1,
                "host-high-cpu-p1",
                "cpu < 65 for 10m",
                candidateAt,
                candidateAt.plusSeconds(600),
                true,
                AlarmRecoveryState.PENDING,
                null,
                false,
                null,
                null);
        when(recoveryService.begin(any(NormalizedAlarmEvent.class), any(), eq(activeState)))
                .thenReturn(recoveryState);
        AlertWorkflowFactory factory = mock(AlertWorkflowFactory.class);
        AlertWorkflowService service = workflowService(
                redisTemplate,
                properties,
                factory,
                new WorkflowNodeExecutor(),
                auditService,
                engine,
                activeAlarmStore,
                null,
                MemoryExtractor.noop(),
                null,
                recoveryService);

        List<NodeResult> results = service.process(new NormalizedAlarmEvent(
                "alarm-pending",
                "fp-pending",
                "HostHighCpuUsageP1",
                "prometheus",
                "resolved",
                AlarmSeverity.INFO,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                "node-a",
                "cluster-a",
                "prod",
                "infra",
                "host.cpu.usage_percent",
                30.0,
                70.0,
                "%",
                "10m",
                Map.of(),
                Map.of(),
                "runbook-host-cpu-high",
                AlarmStatus.RESOLVED,
                Instant.now(),
                "recovered",
                Map.of()));

        assertEquals(1, results.size());
        assertEquals("alarmRecoveryPending", results.get(0).nodeName());
        assertEquals(true, results.get(0).payload().get("manualConfirmationRequired"));
        assertEquals("P1", results.get(0).payload().get("severity"));
        verify(factory, never()).buildWorkflow();
        verify(auditService)
                .recordAlarmExecution(
                        anyString(),
                        eq("RECOVERY_PENDING"),
                        eq(false),
                        eq(true),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    private static NormalizedAlarmEvent cpuEvent(double currentValue) {
        return new NormalizedAlarmEvent(
                "alarm-cpu",
                "fp-cpu",
                "HostHighCpuUsageP1",
                "prometheus",
                "warning",
                null,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                "node-a",
                "lab-cluster",
                "monitoring",
                "infra-exporter",
                "host.cpu.usage_percent",
                currentValue,
                70.0,
                "%",
                "10m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-host-cpu-high",
                null,
                Instant.now(),
                "cpu high",
                Map.of());
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine) {
        return workflowService(
                redisTemplate,
                properties,
                factory,
                executor,
                auditService,
                engine,
                new ActiveAlarmStore(redisTemplate, new ObjectMapper(), properties),
                null,
                MemoryExtractor.noop(),
                null,
                null,
                null,
                null,
                null);
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine,
            ActiveAlarmStore activeAlarmStore,
            AlertMemoryService alertMemoryService,
            MemoryExtractor memoryExtractor) {
        return workflowService(
                redisTemplate,
                properties,
                factory,
                executor,
                auditService,
                engine,
                activeAlarmStore,
                alertMemoryService,
                memoryExtractor,
                null,
                null,
                null,
                null,
                null);
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine,
            ActiveAlarmStore activeAlarmStore,
            AlertMemoryService alertMemoryService,
            MemoryExtractor memoryExtractor,
            AlarmSilenceApprovalStore silenceApprovalStore) {
        return workflowService(
                redisTemplate,
                properties,
                factory,
                executor,
                auditService,
                engine,
                activeAlarmStore,
                alertMemoryService,
                memoryExtractor,
                silenceApprovalStore,
                null,
                null,
                null,
                null);
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine,
            ActiveAlarmStore activeAlarmStore,
            AlertMemoryService alertMemoryService,
            MemoryExtractor memoryExtractor,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmRecoveryService alarmRecoveryService) {
        return workflowService(
                redisTemplate,
                properties,
                factory,
                executor,
                auditService,
                engine,
                activeAlarmStore,
                alertMemoryService,
                memoryExtractor,
                silenceApprovalStore,
                alarmRecoveryService,
                null,
                null,
                null);
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine,
            ActiveAlarmStore activeAlarmStore,
            AlertMemoryService alertMemoryService,
            MemoryExtractor memoryExtractor,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmRecoveryService alarmRecoveryService,
            AlarmEscalationService alarmEscalationService) {
        return workflowService(
                redisTemplate,
                properties,
                factory,
                executor,
                auditService,
                engine,
                activeAlarmStore,
                alertMemoryService,
                memoryExtractor,
                silenceApprovalStore,
                alarmRecoveryService,
                alarmEscalationService,
                null,
                null);
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine,
            ActiveAlarmStore activeAlarmStore,
            AlertMemoryService alertMemoryService,
            MemoryExtractor memoryExtractor,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmRecoveryService alarmRecoveryService,
            AlarmEscalationService alarmEscalationService,
            AlarmMaintenanceWindowService maintenanceWindowService) {
        return workflowService(
                redisTemplate,
                properties,
                factory,
                executor,
                auditService,
                engine,
                activeAlarmStore,
                alertMemoryService,
                memoryExtractor,
                silenceApprovalStore,
                alarmRecoveryService,
                alarmEscalationService,
                maintenanceWindowService,
                null);
    }

    private static AlertWorkflowService workflowService(
            StringRedisTemplate redisTemplate,
            KubeOnCallProperties properties,
            AlertWorkflowFactory factory,
            WorkflowNodeExecutor executor,
            ExecutionAuditService auditService,
            AlarmPolicyEngine engine,
            ActiveAlarmStore activeAlarmStore,
            AlertMemoryService alertMemoryService,
            MemoryExtractor memoryExtractor,
            AlarmSilenceApprovalStore silenceApprovalStore,
            AlarmRecoveryService alarmRecoveryService,
            AlarmEscalationService alarmEscalationService,
            AlarmMaintenanceWindowService maintenanceWindowService,
            AlarmSuppressionService alarmSuppressionService) {
        AlarmSilenceApprovalStore silenceStore = silenceApprovalStore;
        if (silenceStore == null) {
            silenceStore = mock(AlarmSilenceApprovalStore.class);
            when(silenceStore.find(anyString())).thenReturn(Optional.empty());
        }
        AlarmMaintenanceWindowService maintenanceService = maintenanceWindowService;
        if (maintenanceService == null) {
            maintenanceService = mock(AlarmMaintenanceWindowService.class);
            when(maintenanceService.matchingWindow(any(NormalizedAlarmEvent.class), any(Instant.class)))
                    .thenReturn(Optional.empty());
        }
        AlarmSuppressionService suppressionService = alarmSuppressionService;
        if (suppressionService == null) {
            suppressionService = mock(AlarmSuppressionService.class);
            when(suppressionService.evaluate(any(NormalizedAlarmEvent.class)))
                    .thenReturn(new AlarmSuppressionService.SuppressionDecision(false, null, null, null, null, ""));
        }
        AlarmRecoveryService recoveryService = alarmRecoveryService;
        if (recoveryService == null) {
            recoveryService = mock(AlarmRecoveryService.class);
            when(recoveryService.begin(any(NormalizedAlarmEvent.class), any(), any()))
                    .thenAnswer(invocation -> pendingRecovery(invocation.getArgument(0)));
        }
        AlarmEscalationService escalationService = alarmEscalationService;
        if (escalationService == null) {
            escalationService = mock(AlarmEscalationService.class);
            when(escalationService.escalate(
                            any(),
                            any(),
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(
                            new NodeResult("alarmEscalation", NodeStatus.FAILURE, "escalation unavailable", Map.of()));
        }
        AlarmWorkflowAuditRecorder auditRecorder = new AlarmWorkflowAuditRecorder(auditService);
        AlarmEventPreparationService eventPreparationService =
                new AlarmEventPreparationService(new AlarmFingerprintService(), engine, activeAlarmStore);
        AlertWorkflowMemory workflowMemory = new AlertWorkflowMemory(
                alertMemoryService == null ? mock(AlertMemoryService.class) : alertMemoryService, memoryExtractor);
        AlarmNodeNoiseSuppression nodeNoiseSuppression = new AlarmNodeNoiseSuppression(redisTemplate, properties);
        return new AlertWorkflowService(
                eventPreparationService,
                new AlertWorkflowPreflight(
                        workflowMemory,
                        new AlarmWorkflowRecoveryHandler(eventPreparationService, recoveryService, auditRecorder),
                        new AlarmWorkflowSuppressionHandler(
                                maintenanceService, suppressionService, nodeNoiseSuppression, auditRecorder),
                        new AlarmWorkflowDeduplicator(redisTemplate, properties, auditRecorder)),
                new AlertWorkflowCoordinator(
                        new AlertWorkflowRunner(factory, executor, properties),
                        auditRecorder,
                        workflowMemory,
                        silenceStore,
                        new AlarmWorkflowEscalation(redisTemplate, properties, escalationService)));
    }

    private static AlarmRecoveryState pendingRecovery(NormalizedAlarmEvent event) {
        Instant now = Instant.now();
        return new AlarmRecoveryState(
                event.fingerprint(),
                event.alarmId(),
                event.severity(),
                null,
                null,
                now,
                now.plusSeconds(60),
                false,
                AlarmRecoveryState.PENDING,
                null,
                false,
                null,
                null);
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
                "HostHighCpuUsageP1",
                "host.cpu.usage_percent",
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                ">",
                70.0,
                "10m",
                Map.of());
        com.kubeoncall.alarm.domain.AlarmAction action =
                new com.kubeoncall.alarm.domain.AlarmAction("host-resource", "oncall", false, false, List.of());
        AlarmPolicy p1 = new AlarmPolicy(
                "host-high-cpu-p1",
                "HostHighCpuUsageP1",
                "host",
                "host.cpu.usage_percent",
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                AlarmSeverity.P1,
                condition,
                "cpu > 70",
                "15m",
                "cpu < 65",
                "runbook-host-cpu-high",
                "infra",
                action,
                Map.of("team", "infra"));
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
                "HostHighCpuUsageP0",
                "host.cpu.usage_percent",
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                ">",
                85.0,
                "10m",
                Map.of());
        com.kubeoncall.alarm.domain.AlarmAction action =
                new com.kubeoncall.alarm.domain.AlarmAction("host-resource", "oncall", false, false, List.of());
        AlarmPolicy p0 = new AlarmPolicy(
                "host-high-cpu-p0",
                "HostHighCpuUsageP0",
                "host",
                "host.cpu.usage_percent",
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                AlarmSeverity.P0,
                condition,
                "cpu > 85",
                "15m",
                "cpu < 75",
                "runbook-host-cpu-high",
                "infra",
                action,
                Map.of("team", "infra"));
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
