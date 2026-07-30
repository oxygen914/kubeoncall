package com.kubeoncall.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.evidence.EvidenceCollectionStatus;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceResource;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.evidence.EvidenceWindow;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

class ExecutorExecuteNodeTest {

    @Test
    void shouldReturnFailureWhenToolCallFails() {
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(new FailingExecutor()));
        GraphState state = new GraphState();
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default", "keyword", "error", "lookbackMinutes", 10),
                                List.of("namespace", "keyword", "lookbackMinutes"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "execute",
                                null));
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryLogs", "complete", true));
        state.getContext().put("executorToolDefinition", readOnlyQueryLogs());
        state.setCurrentLoop(1);

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
    }

    @Test
    void shouldReturnRetryWithStructuredMetadataForTransientFailure() {
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(new TransientFailingExecutor()));
        GraphState state = new GraphState();
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default", "keyword", "error", "lookbackMinutes", 10),
                                List.of("namespace", "keyword", "lookbackMinutes"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "execute",
                                null));
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryLogs", "complete", true));
        state.getContext().put("executorToolDefinition", readOnlyQueryLogs());
        state.setCurrentLoop(0);

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.RETRY, result.status());
        assertEquals("TRANSIENT_TOOL_FAILURE", result.retryReason());
        assertEquals("RETRY_TOOL_CALL", result.retryStrategy());
    }

    @Test
    void shouldReturnRetryWhenExecutionPayloadIncomplete() {
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(new SuccessExecutor()));
        GraphState state = new GraphState();
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default", "keyword", "error", "lookbackMinutes", 10),
                                List.of("namespace", "keyword", "lookbackMinutes"),
                                List.of(),
                                Map.of("namespace", "from_planner"),
                                "execute",
                                null));
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryLogs", "complete", false));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.RETRY, result.status());
        assertEquals("INCOMPLETE_EXECUTION_PAYLOAD", result.retryReason());
        assertEquals("REPLAN_EXECUTION", result.retryStrategy());
    }

    @Test
    void shouldSatisfyReadOnlyLogQueryFromSuccessfulUnifiedEvidence() {
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(new FailingExecutor()));
        GraphState state = new GraphState();
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "kubeoncall-system"),
                                List.of(),
                                List.of(),
                                Map.of(),
                                "inspect logs",
                                null));
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryLogs", "complete", true));
        state.getContext().put("executorToolDefinition", readOnlyQueryLogs());
        state.getContext().put("evidenceItems", List.of(successfulLogEvidence()));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertTrue((Boolean) result.payload().get("evidenceBacked"));
        assertEquals("unified-evidence", ((Map<?, ?>) result.payload().get("result")).get("source"));
    }

    @Test
    void shouldAttachStableOperationIdToMutatingToolCall() {
        CapturingExecutor executor = new CapturingExecutor();
        OperationClosureService closureService = org.mockito.Mockito.mock(OperationClosureService.class);
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(executor), closureService);
        GraphState state = new GraphState();
        state.setExecutionId("exec-1");
        state.setCurrentTask(new com.kubeoncall.domain.task.Task(
                "task-1",
                "scale",
                com.kubeoncall.domain.task.TaskType.SCALE_WORKLOAD,
                com.kubeoncall.domain.task.RiskLevel.HIGH,
                "payment-service",
                Map.of("namespace", "prod", "replicas", 3),
                new com.kubeoncall.domain.task.SopReference("sop-1", "scale", "1", "test")));
        ExecutionPlan plan = new ExecutionPlan(
                "kubernetes",
                "scaleWorkload",
                Map.of("namespace", "prod", "replicas", 3),
                List.of("namespace", "replicas"),
                List.of(),
                Map.of(),
                "scale",
                null);
        com.kubeoncall.tool.ToolDefinition definition = new com.kubeoncall.tool.ToolDefinition(
                "kubernetes.scaleWorkload", "kubernetes", "scale", false, true, List.of(), List.of(), List.of());
        state.getContext().put("executionPlan", plan);
        state.getContext().put("executorToolDefinition", definition);
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.scaleWorkload", "complete", true));
        state.getContext().put("plannerMode", "REAL_MODEL");
        org.mockito.Mockito.when(closureService.prepare(state, plan, definition))
                .thenReturn(OperationClosureService.Preparation.ready(Map.of(
                        "operationId",
                        "exec-1:task-1:kubernetes.scaleWorkload",
                        "mutationGuard",
                        Map.of(
                                "expectedResourceUid",
                                "deployment-uid",
                                "expectedGeneration",
                                7L,
                                "resourceKind",
                                "Deployment"))));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("exec-1:task-1:kubernetes.scaleWorkload", executor.parameters.get("operationId"));
        assertEquals("payment-service", executor.parameters.get("target"));
        assertEquals("deployment-uid", executor.parameters.get("expectedResourceUid"));
        assertEquals(7L, executor.parameters.get("expectedGeneration"));
    }

    @Test
    void shouldResolveAmbiguousMutationFailureThroughIndependentClosureVerification() {
        TransientFailingExecutor executor = new TransientFailingExecutor();
        OperationClosureService closureService = org.mockito.Mockito.mock(OperationClosureService.class);
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(executor), closureService);
        GraphState state = new GraphState();
        state.setExecutionId("exec-1");
        state.setCurrentLoop(1);
        state.setCurrentTask(new com.kubeoncall.domain.task.Task(
                "task-1",
                "scale",
                com.kubeoncall.domain.task.TaskType.SCALE_WORKLOAD,
                com.kubeoncall.domain.task.RiskLevel.HIGH,
                "payment-service",
                Map.of("namespace", "prod", "replicas", 3),
                new com.kubeoncall.domain.task.SopReference("sop-1", "scale", "1", "test")));
        ExecutionPlan plan = new ExecutionPlan(
                "kubernetes",
                "scaleWorkload",
                Map.of("namespace", "prod", "replicas", 3),
                List.of("namespace", "replicas"),
                List.of(),
                Map.of(),
                "scale",
                null);
        ToolDefinition definition = new ToolDefinition(
                "kubernetes.scaleWorkload", "kubernetes", "scale", false, true, List.of(), List.of(), List.of());
        state.getContext().put("executionPlan", plan);
        state.getContext().put("executorToolDefinition", definition);
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.scaleWorkload", "complete", true));
        state.getContext().put("plannerMode", "REAL_MODEL");
        org.mockito.Mockito.when(closureService.prepare(state, plan, definition))
                .thenReturn(OperationClosureService.Preparation.ready(Map.of(
                        "operationId",
                        "exec-1:task-1:kubernetes.scaleWorkload",
                        "mutationGuard",
                        Map.of("expectedResourceUid", "uid-1", "expectedGeneration", 1L))));
        org.mockito.Mockito.when(closureService.close(state))
                .thenReturn(OperationClosureService.Outcome.success(Map.of("status", "VERIFIED")));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        org.mockito.Mockito.verify(closureService).close(state);
    }

    @Test
    void shouldRecheckSkillWhitelistAtTheToolDispatchBoundary() {
        CapturingExecutor executor = new CapturingExecutor();
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(executor));
        GraphState state = preparedReadOnlyState();
        state.getContext().put("activatedSkillIds", List.of("node-runtime-pressure-triage"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.describeResource"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("SKILL_TOOL_NOT_ALLOWED", result.payload().get("reason"));
        assertTrue(executor.parameters.isEmpty());
    }

    @Test
    void shouldRecheckSkillMaxRiskAtTheToolDispatchBoundary() {
        CapturingExecutor executor = new CapturingExecutor();
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(executor));
        GraphState state = preparedReadOnlyState();
        state.setCurrentTask(new com.kubeoncall.domain.task.Task(
                "task-risk",
                "query logs",
                com.kubeoncall.domain.task.TaskType.QUERY_LOGS,
                com.kubeoncall.domain.task.RiskLevel.MEDIUM,
                "worker-1",
                Map.of("namespace", "default"),
                null));
        state.getContext().put("activatedSkillIds", List.of("node-runtime-pressure-triage"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.queryLogs"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("SKILL_MAX_RISK_EXCEEDED", result.payload().get("reason"));
        assertTrue(executor.parameters.isEmpty());
    }

    @Test
    void shouldRejectMismatchedToolDefinitionAtTheDispatchBoundary() {
        CapturingExecutor executor = new CapturingExecutor();
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(executor));
        GraphState state = preparedReadOnlyState();
        state.getContext()
                .put(
                        "executorToolDefinition",
                        new ToolDefinition(
                                "kubernetes.describeResource",
                                "kubernetes",
                                "describe",
                                true,
                                false,
                                List.of(),
                                List.of(),
                                List.of()));
        state.getContext().put("activatedSkillIds", List.of("node-runtime-pressure-triage"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.describeResource"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("EXECUTOR_TOOL_DEFINITION_MISMATCH", result.payload().get("reason"));
        assertTrue(executor.parameters.isEmpty());
    }

    @Test
    void shouldRejectMissingTaskRiskAtTheDispatchBoundary() {
        CapturingExecutor executor = new CapturingExecutor();
        ExecutorExecuteNode node = new ExecutorExecuteNode(List.of(executor));
        GraphState state = preparedReadOnlyState();
        state.setCurrentTask(new com.kubeoncall.domain.task.Task(
                "task-no-risk",
                "query logs",
                com.kubeoncall.domain.task.TaskType.QUERY_LOGS,
                null,
                "worker-1",
                Map.of("namespace", "default"),
                null));
        state.getContext().put("activatedSkillIds", List.of("node-runtime-pressure-triage"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.queryLogs"));
        state.getContext().put("activatedSkillMaxRisk", "LOW");

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.FAILURE, result.status());
        assertEquals("TASK_RISK_MISSING", result.payload().get("reason"));
        assertTrue(executor.parameters.isEmpty());
    }

    private static GraphState preparedReadOnlyState() {
        GraphState state = new GraphState();
        state.setCurrentTask(new com.kubeoncall.domain.task.Task(
                "task-read",
                "query logs",
                com.kubeoncall.domain.task.TaskType.QUERY_LOGS,
                com.kubeoncall.domain.task.RiskLevel.LOW,
                "worker-1",
                Map.of("namespace", "default"),
                null));
        state.getContext()
                .put(
                        "executionPlan",
                        new ExecutionPlan(
                                "kubernetes",
                                "queryLogs",
                                Map.of("namespace", "default"),
                                List.of(),
                                List.of(),
                                Map.of(),
                                "query logs",
                                null));
        state.getContext().put("executorPayload", Map.of("toolName", "kubernetes.queryLogs", "complete", true));
        state.getContext().put("executorToolDefinition", readOnlyQueryLogs());
        return state;
    }

    private static ToolDefinition readOnlyQueryLogs() {
        return new ToolDefinition(
                "kubernetes.queryLogs", "kubernetes", "query logs", true, false, List.of(), List.of(), List.of());
    }

    private static EvidenceItem successfulLogEvidence() {
        Instant now = Instant.now();
        return new EvidenceItem(
                "evd_1",
                "exec_1",
                EvidenceType.POD_LOG,
                "loki",
                "local",
                "kubeoncall-system",
                new EvidenceResource("", "", ""),
                now,
                new EvidenceWindow(now.minusSeconds(60), now),
                "1 relevant log line",
                "redacted log line",
                Map.of(),
                0,
                true,
                false,
                "hash",
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of());
    }

    private static class FailingExecutor implements ToolExecutor {

        @Override
        public String getExecutorKind() {
            return "kubernetes";
        }

        @Override
        public List<ToolDefinition> supportedTools() {
            return List.of();
        }

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            return Map.of(
                    "status", "failed",
                    "httpStatus", 500,
                    "errorMessage", "upstream unavailable");
        }
    }

    private static class TransientFailingExecutor implements ToolExecutor {

        @Override
        public String getExecutorKind() {
            return "kubernetes";
        }

        @Override
        public List<ToolDefinition> supportedTools() {
            return List.of();
        }

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            return Map.of(
                    "status", "failed",
                    "httpStatus", 503,
                    "errorMessage", "temporary unavailable");
        }
    }

    private static class SuccessExecutor implements ToolExecutor {

        @Override
        public String getExecutorKind() {
            return "kubernetes";
        }

        @Override
        public List<ToolDefinition> supportedTools() {
            return List.of();
        }

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            return Map.of("status", "success", "httpStatus", 200, "response", Map.of("ok", true));
        }
    }

    private static final class CapturingExecutor extends SuccessExecutor {

        private Map<String, Object> parameters = Map.of();

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            this.parameters = Map.copyOf(parameters);
            return super.execute(action, parameters);
        }
    }
}
