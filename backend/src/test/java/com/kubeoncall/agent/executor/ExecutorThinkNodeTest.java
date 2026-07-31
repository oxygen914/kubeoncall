package com.kubeoncall.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
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
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;

class ExecutorThinkNodeTest {

    @Test
    void shouldUseSupplementalSignalsBeforeRetry() {
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.scaleWorkload",
                "kubernetes",
                "Scale deployment",
                false,
                true,
                List.of(TaskType.SCALE_WORKLOAD),
                List.of("namespace", "replicas"),
                List.of("kubernetes"));
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        when(catalog.findExecutorTool("kubernetes", "scaleWorkload")).thenReturn(tool);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory());

        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-1",
                "scale service",
                TaskType.SCALE_WORKLOAD,
                RiskLevel.MEDIUM,
                "order-service",
                Map.of("namespace", "prod"),
                new SopReference("SOP-1", "scale", "v1", "rag:sop")));
        Map<String, Object> plannerKnowledge = new LinkedHashMap<>();
        plannerKnowledge.put(
                "supplementalSignals", Map.of("replicas", 5, "scaleHintSource", "topology.getServiceTopology"));
        state.getContext().put("plannerKnowledge", plannerKnowledge);

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        ExecutionPlan plan = (ExecutionPlan) state.getContext().get("executionPlan");
        assertEquals(5, plan.parameters().get("replicas"));
        assertEquals("tool:topology.getServiceTopology", plan.parameterSources().get("replicas"));
        assertTrue(plan.missingParameters().isEmpty());
    }

    @Test
    void shouldReturnStructuredRetryWhenMissingParametersPersist() {
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.scaleWorkload",
                "kubernetes",
                "Scale deployment",
                false,
                true,
                List.of(TaskType.SCALE_WORKLOAD),
                List.of("namespace", "replicas"),
                List.of("kubernetes"));
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        when(catalog.findExecutorTool("kubernetes", "scaleWorkload")).thenReturn(tool);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory());

        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-2",
                "scale service",
                TaskType.SCALE_WORKLOAD,
                RiskLevel.MEDIUM,
                "order-service",
                Map.of(),
                new SopReference("SOP-1", "scale", "v1", "rag:sop")));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.RETRY, result.status());
        assertEquals("MISSING_PARAMETERS", result.retryReason());
        assertEquals("QUERY_ADDITIONAL_CONTEXT", result.retryStrategy());
        assertTrue(((List<?>) result.payload().get("missingParameters")).contains("namespace"));
        assertTrue(((List<?>) result.payload().get("missingParameters")).contains("replicas"));
    }

    @Test
    void shouldRejectWhenPlannedToolIsNotInSkillWhitelist() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        List<String> whitelist = List.of("kubernetes.describeResource");
        when(catalog.findExecutorTool(eq("kubernetes"), eq("scaleWorkload"), eq(whitelist)))
                .thenReturn(null);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory(), metricsService);

        GraphState state = new GraphState();
        state.getContext().put("activatedSkillToolWhitelist", whitelist);
        state.setCurrentTask(new Task(
                "task-3",
                "scale service",
                TaskType.SCALE_WORKLOAD,
                RiskLevel.MEDIUM,
                "order-service",
                Map.of("namespace", "prod", "replicas", 3),
                new SopReference("SOP-1", "scale", "v1", "rag:sop")));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("SKILL_TOOL_NOT_ALLOWED", result.payload().get("reason"));
        assertEquals("kubernetes.scaleWorkload", result.payload().get("plannedTool"));
        assertEquals(whitelist, result.payload().get("allowedTools"));
        assertTrue(state.getContext().containsKey("skillToolWhitelistViolation"));
        assertFalse(state.getContext().containsKey("executorPayload"));
        assertNull(state.getContext().get("executionPlan"));
        verify(catalog, never()).findExecutorTool("kubernetes", "scaleWorkload");
        verify(metricsService).recordSkillGovernance("whitelist_violation", "rejected");
    }

    @Test
    void shouldUsePlannedToolWhenItIsInSkillWhitelist() {
        ToolDefinition tool = new ToolDefinition(
                "kubernetes.scaleWorkload",
                "kubernetes",
                "Scale deployment",
                false,
                true,
                List.of(TaskType.SCALE_WORKLOAD),
                List.of("namespace", "replicas"),
                List.of("kubernetes"));
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        List<String> whitelist = List.of("kubernetes.scaleWorkload");
        when(catalog.findExecutorTool(eq("kubernetes"), eq("scaleWorkload"), eq(whitelist)))
                .thenReturn(tool);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory());

        GraphState state = new GraphState();
        state.getContext().put("activatedSkillToolWhitelist", whitelist);
        state.setCurrentTask(new Task(
                "task-4",
                "scale service",
                TaskType.SCALE_WORKLOAD,
                RiskLevel.MEDIUM,
                "order-service",
                Map.of("namespace", "prod", "replicas", 3),
                new SopReference("SOP-1", "scale", "v1", "rag:sop")));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertFalse(state.getContext().containsKey("skillToolWhitelistViolation"));
        ExecutionPlan plan = (ExecutionPlan) state.getContext().get("executionPlan");
        assertEquals("scaleWorkload", plan.action());
        assertEquals("kubernetes.scaleWorkload", result.payload().get("toolName"));
        verify(catalog, never()).findExecutorTool("kubernetes", "scaleWorkload");
    }

    @Test
    void shouldRejectTaskAboveActivatedSkillRiskBeforeToolResolution() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory(), metricsService);
        GraphState state = new GraphState();
        state.getContext().put("activatedSkillIds", List.of("node-runtime-pressure-triage"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.queryLogs"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");
        state.setCurrentTask(new Task(
                "task-5",
                "query node logs",
                TaskType.QUERY_LOGS,
                RiskLevel.MEDIUM,
                "worker-1",
                Map.of("namespace", "default"),
                null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("SKILL_MAX_RISK_EXCEEDED", result.payload().get("reason"));
        assertFalse(state.getContext().containsKey("executorPayload"));
        verify(catalog, never()).findExecutorTool("kubernetes", "queryLogs", List.of("kubernetes.queryLogs"));
        verify(metricsService).recordSkillGovernance("max_risk_violation", "rejected");
    }

    @Test
    void shouldFailClosedWhenActivatedSkillWhitelistIsEmpty() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory(), metricsService);
        GraphState state = new GraphState();
        state.getContext().put("activatedSkillIds", List.of("broken-skill"));
        state.getContext().put("activatedSkillToolWhitelist", List.of());
        state.getContext().put("activatedSkillMaxRisk", "LOW");
        state.setCurrentTask(new Task(
                "task-6", "query node metrics", TaskType.QUERY_METRICS, RiskLevel.LOW, "worker-1", Map.of(), null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("SKILL_TOOL_WHITELIST_EMPTY", result.payload().get("reason"));
        assertFalse(state.getContext().containsKey("executorPayload"));
        verify(metricsService).recordSkillGovernance("whitelist_empty", "rejected");
    }

    @Test
    void shouldFailClosedWhenActivatedSkillMaxRiskIsMissing() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory(), metricsService);
        GraphState state = new GraphState();
        state.getContext().put("activatedSkillIds", List.of("broken-skill"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.queryMetricsContext"));
        state.setCurrentTask(new Task(
                "task-7", "query node metrics", TaskType.QUERY_METRICS, RiskLevel.LOW, "worker-1", Map.of(), null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("SKILL_MAX_RISK_MISSING", result.payload().get("reason"));
        verify(metricsService).recordSkillGovernance("max_risk_missing", "rejected");
    }

    @Test
    void shouldFailClosedWhenActivatedSkillTaskRiskIsMissing() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        ExecutorThinkNode node = new ExecutorThinkNode(catalog, new ExecutorPlanFactory(), metricsService);
        GraphState state = new GraphState();
        state.getContext().put("activatedSkillIds", List.of("broken-skill"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.queryMetricsContext"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");
        state.setCurrentTask(
                new Task("task-8", "query node metrics", TaskType.QUERY_METRICS, null, "worker-1", Map.of(), null));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("TASK_RISK_MISSING", result.payload().get("reason"));
        verify(metricsService).recordSkillGovernance("task_risk_missing", "rejected");
    }
}
