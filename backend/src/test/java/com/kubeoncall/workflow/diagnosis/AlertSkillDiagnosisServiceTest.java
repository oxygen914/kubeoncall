package com.kubeoncall.workflow.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.planner.PlannerAgent;
import com.kubeoncall.agent.verifier.VerifierThinkNode;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConfidenceAssessment;
import com.kubeoncall.evidence.EvidenceCollectionStatus;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceResource;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.evidence.EvidenceWindow;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.Skill;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillSource;
import com.kubeoncall.workflow.AlertWorkflowContext;

class AlertSkillDiagnosisServiceTest {

    @Test
    void shouldRunReadOnlyPlanUnderOriginalSkillBoundaryAndVerifyRealEvidence() {
        PlannerAgent planner = mock(PlannerAgent.class);
        ExecutorAgent executor = mock(ExecutorAgent.class);
        VerifierThinkNode verifier = mock(VerifierThinkNode.class);
        SkillActivation activation = activation();
        EvidenceItem evidence = evidence("evd-node-1");
        when(planner.run(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.rolloutRestart"));
            state.getContext().put("plannerSource", "llm");
            state.getContext().put("plannerMode", "REAL_MODEL");
            state.getContext().put("plannerDegraded", false);
            state.getContext().put("evidenceItems", List.of(evidence));
            state.getContext().put("conclusions", List.of(conclusion(evidence.evidenceId())));
            state.setCurrentTask(queryTask(RiskLevel.LOW));
            state.setStatus(GraphStatus.SUCCESS);
            return state;
        });
        when(executor.plan(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            assertEquals(activation.toolWhitelist(), state.getContext().get("activatedSkillToolWhitelist"));
            assertEquals(true, state.getContext().get("compatibilityReadOnly"));
            state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryMetricsContext"));
            state.setStatus(GraphStatus.SUCCESS);
            return state;
        });
        when(verifier.execute(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("verifierDecision", "ALLOW");
            return new NodeResult("verifierThinkNode", NodeStatus.SUCCESS, "allowed", Map.of());
        });
        when(executor.executePrepared(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("executorResult", Map.of("status", "success", "httpStatus", 200));
            state.setStatus(GraphStatus.SUCCESS);
            return state;
        });
        when(verifier.verifyEvidence(any()))
                .thenReturn(new VerifierThinkNode.EvidenceVerification(
                        "VERIFIED",
                        "CONCLUSION_EVIDENCE_MATCHED",
                        List.of(evidence.evidenceId()),
                        List.of(evidence.evidenceId()),
                        List.of(),
                        0));
        AlertSkillDiagnosisService service = service(planner, executor, verifier);

        AlertSkillDiagnosisService.Outcome outcome = service.diagnose(context(), activation);

        assertTrue(outcome.attempted());
        assertTrue(outcome.verified());
        assertEquals("SKILL_AGENT_VERIFIED", outcome.strategy());
        assertEquals("VERIFIED", outcome.verification().get("status"));
        assertEquals(false, outcome.fallback().get("used"));
        assertEquals(activation.toolWhitelist(), outcome.plan().get("allowedTools"));
        assertEquals("kubernetes.queryMetricsContext", outcome.plan().get("selectedTool"));
        assertTrue(outcome.invokedTools().contains("kubernetes.describeResource"));
        assertTrue(outcome.agentExecutionId().startsWith("agd_"));
    }

    @Test
    void shouldBlockModelPlannedMutationAndUseRunbookFallback() {
        PlannerAgent planner = mock(PlannerAgent.class);
        ExecutorAgent executor = mock(ExecutorAgent.class);
        VerifierThinkNode verifier = mock(VerifierThinkNode.class);
        when(planner.run(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setCurrentTask(new Task(
                    "task-mutate",
                    "restart node agent",
                    TaskType.RESTART_SERVICE,
                    RiskLevel.HIGH,
                    "worker-1",
                    Map.of(),
                    null));
            state.setStatus(GraphStatus.SUCCESS);
            return state;
        });
        when(verifier.verifyEvidence(any()))
                .thenReturn(new VerifierThinkNode.EvidenceVerification(
                        "INSUFFICIENT", "NO_SUCCEEDED_EVIDENCE", List.of(), List.of(), List.of(), 0));
        AlertSkillDiagnosisService service = service(planner, executor, verifier);

        AlertSkillDiagnosisService.Outcome outcome = service.diagnose(context(), activation());

        assertFalse(outcome.verified());
        assertEquals("SKILL_DETERMINISTIC_RUNBOOK_FALLBACK", outcome.strategy());
        assertEquals(true, outcome.fallback().get("used"));
        assertEquals("NON_READ_ONLY_TASK_BLOCKED", outcome.fallback().get("reason"));
        assertEquals("runbook-time-sync", outcome.fallback().get("runbookId"));
        assertEquals("CURRENT_STATE_AND_RUNBOOK", outcome.fallback().get("mode"));
        assertEquals("NodeClockOffsetHigh", ((Map<?, ?>) outcome.fallback().get("baselineDiagnosis")).get("alertName"));
        verify(executor, never()).plan(any());
        verify(verifier, never()).execute(any());
    }

    @Test
    void shouldFallbackWhenReadOnlyQueryExecutionFails() {
        PlannerAgent planner = mock(PlannerAgent.class);
        ExecutorAgent executor = mock(ExecutorAgent.class);
        VerifierThinkNode verifier = mock(VerifierThinkNode.class);
        EvidenceItem evidence = evidence("evd-node-2");
        when(planner.run(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("plannerMode", "REAL_MODEL");
            state.getContext().put("plannerDegraded", false);
            state.getContext().put("evidenceItems", List.of(evidence));
            state.getContext().put("conclusions", List.of(conclusion(evidence.evidenceId())));
            state.setCurrentTask(queryTask(RiskLevel.LOW));
            state.setStatus(GraphStatus.SUCCESS);
            return state;
        });
        when(executor.plan(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryMetricsContext"));
            state.setStatus(GraphStatus.SUCCESS);
            return state;
        });
        when(verifier.execute(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("verifierDecision", "ALLOW");
            return new NodeResult("verifierThinkNode", NodeStatus.SUCCESS, "allowed", Map.of());
        });
        when(executor.executePrepared(any())).thenAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.getContext().put("executorResult", Map.of("status", "failed", "httpStatus", 503));
            state.setStatus(GraphStatus.FAILED);
            return state;
        });
        when(verifier.verifyEvidence(any()))
                .thenReturn(new VerifierThinkNode.EvidenceVerification(
                        "VERIFIED",
                        "CONCLUSION_EVIDENCE_MATCHED",
                        List.of(evidence.evidenceId()),
                        List.of(evidence.evidenceId()),
                        List.of(),
                        0));
        AlertSkillDiagnosisService service = service(planner, executor, verifier);

        AlertSkillDiagnosisService.Outcome outcome = service.diagnose(context(), activation());

        assertFalse(outcome.verified());
        assertEquals("PARTIAL", outcome.verification().get("status"));
        assertEquals("READ_ONLY_QUERY_FAILED", outcome.fallback().get("reason"));
        assertEquals(true, outcome.fallback().get("used"));
        assertEquals("CURRENT_STATE_AND_RUNBOOK", outcome.fallback().get("mode"));
    }

    private static AlertSkillDiagnosisService service(
            PlannerAgent planner, ExecutorAgent executor, VerifierThinkNode verifier) {
        return new AlertSkillDiagnosisService(
                planner, executor, verifier, new KubeOnCallProperties(), mock(KubeOnCallMetricsService.class));
    }

    private static SkillActivation activation() {
        List<String> tools = List.of(
                "knowledge.searchSop",
                "kubernetes.describeResource",
                "kubernetes.queryMetricsContext",
                "prometheus.rangeQuery");
        Skill skill = new Skill(
                "node-runtime-pressure-triage",
                "Node Runtime Pressure Triage",
                "v1",
                SkillSource.BUILTIN,
                "skills/node-runtime-pressure-triage/SKILL.md",
                "Diagnose node runtime pressure",
                List.of("node clock offset"),
                List.of(),
                List.of("NODE"),
                List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS),
                List.of("node"),
                RiskLevel.LOW,
                tools,
                "Use read-only evidence.",
                Map.of());
        return new SkillActivation(
                List.of(skill),
                List.of(Map.of("id", skill.id(), "matchSource", "ALERT_NAME")),
                List.of(skill.id()),
                tools,
                RiskLevel.LOW,
                skill.body());
    }

    private static Task queryTask(RiskLevel risk) {
        return new Task(
                "task-query",
                "query node metrics",
                TaskType.QUERY_METRICS,
                risk,
                "worker-1",
                Map.of("cluster", "prod"),
                null);
    }

    private static EvidenceItem evidence(String evidenceId) {
        Instant now = Instant.now();
        return new EvidenceItem(
                evidenceId,
                "agent-exec",
                EvidenceType.RESOURCE_STATE,
                "kubernetes-api",
                "prod",
                "",
                new EvidenceResource("Node", "worker-1", "node-uid"),
                now,
                new EvidenceWindow(now.minusSeconds(60), now),
                "Node Ready",
                "Ready=True",
                Map.of(),
                0,
                false,
                false,
                "hash-" + evidenceId,
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of());
    }

    private static AiConclusion conclusion(String evidenceId) {
        return new AiConclusion(
                "con-node",
                "agent-exec",
                "Current node state supports the diagnosis",
                "P3",
                "SUPPORTED",
                List.of(evidenceId),
                List.of(),
                new ConfidenceAssessment(0.8, "HIGH", Map.of()),
                Map.of("mode", "REAL_MODEL"),
                null);
    }

    private static AlertWorkflowContext context() {
        Instant now = Instant.parse("2026-07-30T06:00:00Z");
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "alarm-node-1",
                "fp-node-1",
                "NodeClockOffsetHigh",
                "prometheus",
                "warning",
                AlarmSeverity.P2,
                AlarmResourceType.NODE,
                "worker-1",
                "prod",
                null,
                null,
                "node.time.clock_offset_seconds",
                0.8,
                0.5,
                "seconds",
                "5m",
                Map.of("resource_uid", "node-uid"),
                Map.of(),
                "runbook-time-sync",
                AlarmStatus.FIRING,
                now,
                "Node clock offset is high",
                Map.of());
        return new AlertWorkflowContext(null, event, null, now);
    }
}
