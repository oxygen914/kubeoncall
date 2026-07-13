package com.kubeoncall.agent.verifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("APPROVAL_REQUIRED", state.getContext().get("verifierDecision"));
        assertEquals("Verifier requires approval before execution", result.message());
    }

    @Test
    void shouldRequireApprovalWhenTaskRiskExceedsActivatedSkillMaxRisk() {
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
        state.getContext().put("activatedSkillIds", List.of("payment-oom-triage"));
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

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("APPROVAL_REQUIRED", state.getContext().get("verifierDecision"));
        assertTrue(((List<?>) result.payload().get("riskReasons"))
                .stream().anyMatch(reason -> String.valueOf(reason).contains("maxRisk LOW")));
        assertEquals("LOW", result.payload().get("activatedSkillMaxRisk"));
    }
}
