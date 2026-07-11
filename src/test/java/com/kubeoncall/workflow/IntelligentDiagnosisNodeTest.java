package com.kubeoncall.workflow;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.workflow.node.IntelligentDiagnosisNode;
import com.kubeoncall.skill.Skill;
import com.kubeoncall.skill.SkillSource;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillActivationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntelligentDiagnosisNodeTest {

    @Test
    void shouldConsumePriorHandlingForRepeatedAlarmWithoutExecutingIt() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMemory().setStaleAfterDays(30);
        properties.getMemory().setInjectMaxEntries(3);
        IntelligentDiagnosisNode node = new IntelligentDiagnosisNode(properties, new TokenBudget());
        AlertWorkflowContext context = context();
        context.putAttribute("activeAlarm", activeAlarm(2));
        context.putAttribute("stateCompareResult", Map.of("status", "success"));
        context.putAttribute("knowledgeHints", Map.of("runbookId", "oom"));
        context.putAttribute("alertMemoryEntries", List.of(
                memory("incident-1", MemoryType.INCIDENT_SUMMARY,
                        "Previous OOM ended after memory limit change", Instant.now().minus(45, ChronoUnit.DAYS)),
                memory("pitfall-1", MemoryType.KNOWN_PITFALL,
                        "Restart can hide the leak for one hour", Instant.now().minus(2, ChronoUnit.DAYS)),
                memory("user-1", MemoryType.USER_PREFERENCE,
                        "Prefer restart", Instant.now())
        ));

        NodeResult result = node.execute(context);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals(true, result.payload().get("memoryConsumed"));
        assertEquals(true, result.payload().get("repeatedIncident"));
        assertEquals(2, result.payload().get("memoryConsumedCount"));
        assertEquals("VERIFY_CURRENT_EVIDENCE_BEFORE_REUSING_PRIOR_HANDLING",
                result.payload().get("strategy"));
        assertEquals(List.of("incident-1", "pitfall-1"), result.payload().get("memoryIds"));
        assertTrue(result.payload().get("guardrails") instanceof List<?> guardrails
                && guardrails.stream().anyMatch(value -> String.valueOf(value).contains("Do not execute")));
        assertTrue(result.payload().get("previousHandlingCandidates") instanceof List<?> candidates
                && candidates.get(0) instanceof Map<?, ?> first
                && Boolean.TRUE.equals(first.get("stale")));
        assertEquals(2, context.getAttribute("alertMemoryConsumed"));
        assertEquals(true, context.getAttribute("repeatIncident"));
    }

    @Test
    void shouldDiagnoseFromCurrentEvidenceWhenNoMemoryExists() {
        IntelligentDiagnosisNode node = new IntelligentDiagnosisNode(
                new KubeOnCallProperties(), new TokenBudget());
        AlertWorkflowContext context = context();
        context.putAttribute("stateCompareResult", Map.of("status", "success"));

        NodeResult result = node.execute(context);

        assertEquals("CURRENT_EVIDENCE_ONLY", result.payload().get("strategy"));
        assertEquals(false, result.payload().get("memoryConsumed"));
        assertEquals(false, result.payload().get("requiresLiveValidation"));
        assertFalse((Boolean) result.payload().get("repeatedIncident"));
        assertEquals(List.of("metrics"), result.payload().get("currentEvidenceSources"));
    }

    @Test
    void shouldActivateSkillForAlarmDiagnosis() {
        SkillActivationService activationService = mock(SkillActivationService.class);
        Skill skill = new Skill(
                "payment-oom-triage", "Payment OOM triage", "v1", SkillSource.BUILTIN,
                "skills/payment-oom-triage/SKILL.md", "Payment OOM triage", List.of("oom"),
                List.of("payment-service"), List.of("POD"), com.kubeoncall.domain.task.RiskLevel.MEDIUM,
                List.of("kubernetes.describeResource"), "verify OOM evidence", Map.of());
        when(activationService.activate(any(String.class), any(Map.class))).thenReturn(new SkillActivation(
                List.of(skill), List.of(Map.of("id", "payment-oom-triage")),
                List.of("payment-oom-triage"), List.of("kubernetes.describeResource"),
                com.kubeoncall.domain.task.RiskLevel.MEDIUM, "verify OOM evidence"));
        IntelligentDiagnosisNode node = new IntelligentDiagnosisNode(
                new KubeOnCallProperties(), new TokenBudget(), activationService);
        AlertWorkflowContext context = context();

        NodeResult result = node.execute(context);

        assertEquals(List.of("payment-oom-triage"), result.payload().get("activatedSkillIds"));
        assertEquals(List.of("payment-oom-triage"), context.getAttribute("activatedSkillIds"));
    }

    private static AlertWorkflowContext context() {
        AlarmEvent legacy = new AlarmEvent(
                "alarm-2", "fp-oom", "prometheus", "P1", "payment-pod", "oom", Instant.now(), Map.of());
        NormalizedAlarmEvent normalized = new NormalizedAlarmEvent(
                "alarm-2", "fp-oom", "PodOOMKilled", "prometheus", "warning", AlarmSeverity.P1,
                AlarmResourceType.POD, "payment-pod", "prod", "payments", "payment-service",
                "container_memory_working_set_bytes", 2.0, 1.0, "GiB", "5m",
                Map.of(), Map.of(), "runbook-oom", AlarmStatus.FIRING, Instant.now(), "oom", Map.of());
        return new AlertWorkflowContext(legacy, normalized, null, Instant.now());
    }

    private static ActiveAlarmState activeAlarm(long count) {
        return new ActiveAlarmState(
                "fp-oom", "alarm-2", "PodOOMKilled", "prod", "payments", "payment-service",
                "payment-pod", AlarmSeverity.P1, AlarmStatus.FIRING, "pod-oom",
                Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now(), count);
    }

    private static MemoryEntry memory(String id, MemoryType type, String content, Instant updatedAt) {
        return new MemoryEntry(
                id, type, MemoryScope.FINGERPRINT, "previous oom", content,
                "payment-service", "payment-pod", "fp-oom",
                updatedAt.minus(1, ChronoUnit.DAYS), updatedAt, Map.of());
    }
}
