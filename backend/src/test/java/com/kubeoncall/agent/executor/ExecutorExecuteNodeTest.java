package com.kubeoncall.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
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
        org.mockito.Mockito.when(closureService.prepare(state, plan, definition))
                .thenReturn(OperationClosureService.Preparation.ready(Map.of()));

        NodeResult result = node.execute(state);

        assertEquals(NodeStatus.SUCCESS, result.status());
        assertEquals("exec-1:task-1:kubernetes.scaleWorkload", executor.parameters.get("operationId"));
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
