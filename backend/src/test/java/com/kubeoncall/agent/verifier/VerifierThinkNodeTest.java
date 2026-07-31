package com.kubeoncall.agent.verifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConfidenceAssessment;
import com.kubeoncall.evidence.EvidenceCollectionStatus;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceResource;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.evidence.EvidenceWindow;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;

class VerifierThinkNodeTest {

    @Test
    void shouldReturnSuccessWhenApprovalRequiredDecision() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.rolloutRestart",
                "kubernetes",
                "restart deployment",
                false,
                true,
                List.of(TaskType.RESTART_SERVICE),
                List.of("namespace"),
                List.of("k8s"));
        when(catalog.findExecutorTool("kubernetes", "rolloutRestart")).thenReturn(tool);

        VerifierThinkNode node = new VerifierThinkNode(catalog);
        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-1",
                "restart payment",
                TaskType.RESTART_SERVICE,
                RiskLevel.HIGH,
                "payment-service",
                Map.of("namespace", "default"),
                new SopReference("SOP-RESTART_SERVICE", "restart", "v1", "rag:sop")));
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "rolloutRestart",
                                Map.of("namespace", "default"),
                                List.of("namespace"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "summary",
                                null));
        state.getContext().put("plannerMode", "REAL_MODEL");

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("APPROVAL_REQUIRED", state.getContext().get("verifierDecision"));
        assertEquals("Verifier requires approval before execution", result.message());
    }

    @Test
    void shouldRejectWhenTaskRiskExceedsActivatedSkillMaxRisk() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.queryLogs",
                "kubernetes",
                "query logs",
                true,
                false,
                List.of(TaskType.QUERY_LOGS),
                List.of("namespace"),
                List.of("k8s"));
        when(catalog.findExecutorTool("kubernetes", "queryLogs")).thenReturn(tool);

        VerifierThinkNode node = new VerifierThinkNode(catalog);
        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-2",
                "query logs",
                TaskType.QUERY_LOGS,
                RiskLevel.MEDIUM,
                "order-service",
                Map.of("namespace", "default"),
                new SopReference("SOP-QUERY_LOGS", "logs", "v1", "rag:sop")));
        state.getContext().put("activatedSkillIds", List.of("pod-oom-triage"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default"),
                                List.of("namespace"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "summary",
                                null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("REJECT", state.getContext().get("verifierDecision"));
        assertTrue(((List<?>) result.payload().get("riskReasons"))
                .stream().anyMatch(reason -> String.valueOf(reason).contains("maxRisk LOW")));
        assertEquals("LOW", result.payload().get("activatedSkillMaxRisk"));
    }

    @Test
    void shouldRejectWhenTaskRiskIsMissing() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.queryLogs",
                "kubernetes",
                "query logs",
                true,
                false,
                List.of(TaskType.QUERY_LOGS),
                List.of("namespace"),
                List.of("k8s"));
        when(catalog.findExecutorTool("kubernetes", "queryLogs")).thenReturn(tool);
        VerifierThinkNode node = new VerifierThinkNode(catalog);
        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-no-risk",
                "query logs",
                TaskType.QUERY_LOGS,
                null,
                "order-service",
                Map.of("namespace", "default"),
                null));
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default"),
                                List.of("namespace"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "summary",
                                null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("REJECT", state.getContext().get("verifierDecision"));
        assertTrue(((List<?>) result.payload().get("riskReasons")).contains("Task risk is missing"));
    }

    @Test
    void shouldVerifyConclusionOnlyWhenItsEvidenceReferencesMatchSuccessfulEvidence() {
        VerifierThinkNode node = new VerifierThinkNode(mock(AgentToolCatalog.class));
        GraphState state = new GraphState();
        state.getContext().put("evidenceItems", List.of(successfulEvidence("evd-live-1")));
        state.getContext().put("conclusions", List.of(conclusion("SUPPORTED", List.of("evd-live-1"))));

        VerifierThinkNode.EvidenceVerification verification = node.verifyEvidence(state);

        assertTrue(verification.verified());
        assertEquals("VERIFIED", verification.status());
        assertEquals(List.of("evd-live-1"), verification.matchedEvidenceRefs());
    }

    @Test
    void shouldKeepConclusionPartialWhenEvidenceReferenceWasNotCollected() {
        VerifierThinkNode node = new VerifierThinkNode(mock(AgentToolCatalog.class));
        GraphState state = new GraphState();
        state.getContext().put("evidenceItems", List.of(successfulEvidence("evd-live-1")));
        state.getContext().put("conclusions", List.of(conclusion("SUPPORTED", List.of("evd-invented"))));

        VerifierThinkNode.EvidenceVerification verification = node.verifyEvidence(state);

        assertFalse(verification.verified());
        assertEquals("PARTIAL", verification.status());
        assertEquals("CONCLUSION_HAS_NO_REAL_EVIDENCE_REFERENCE", verification.reason());
        assertEquals(List.of("evd-invented"), verification.missingEvidenceRefs());
    }

    @Test
    void shouldAllowReadOnlyDiagnosisWithoutInventingAnSopReference() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.queryLogs",
                "kubernetes",
                "query logs",
                true,
                false,
                List.of(TaskType.QUERY_LOGS),
                List.of("namespace"),
                List.of("k8s"));
        when(catalog.findExecutorTool("kubernetes", "queryLogs")).thenReturn(tool);
        VerifierThinkNode node = new VerifierThinkNode(catalog);
        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-3",
                "query logs",
                TaskType.QUERY_LOGS,
                RiskLevel.LOW,
                "order-service",
                Map.of("namespace", "default"),
                null));
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default"),
                                List.of("namespace"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "summary",
                                null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("ALLOW", state.getContext().get("verifierDecision"));
    }

    @Test
    void shouldRejectMutationWithoutVersionedSop() {
        VerifierThinkNode node = new VerifierThinkNode(mock(AgentToolCatalog.class));
        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-4",
                "restart order-service",
                TaskType.RESTART_SERVICE,
                RiskLevel.HIGH,
                "order-service",
                Map.of("namespace", "default"),
                null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertTrue(result.message().contains("versioned SOP"));
    }

    private static EvidenceItem successfulEvidence(String evidenceId) {
        Instant now = Instant.now();
        return new EvidenceItem(
                evidenceId,
                "exec-1",
                EvidenceType.RESOURCE_STATE,
                "kubernetes-api",
                "prod",
                "",
                new EvidenceResource("Node", "worker-1", "node-uid"),
                now,
                new EvidenceWindow(now.minusSeconds(60), now),
                "Node is Ready",
                "Ready=True",
                Map.of(),
                0,
                false,
                false,
                "hash",
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of());
    }

    private static AiConclusion conclusion(String status, List<String> evidenceRefs) {
        return new AiConclusion(
                "con-1",
                "exec-1",
                "Node runtime evidence is consistent with the alert",
                "P3",
                status,
                evidenceRefs,
                List.of(),
                new ConfidenceAssessment(0.8, "HIGH", Map.of()),
                Map.of("mode", "REAL_MODEL"),
                null);
    }
}
