package com.kubeoncall.agent.executor;

import com.kubeoncall.agent.node.ExecuteNode;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ExecutorExecuteNode extends ExecuteNode {

    private static final String RETRY_REASON_MISSING_PARAMETERS = "MISSING_PARAMETERS";
    private static final String RETRY_REASON_INCOMPLETE_PAYLOAD = "INCOMPLETE_EXECUTION_PAYLOAD";
    private static final String RETRY_REASON_TRANSIENT_TOOL_FAILURE = "TRANSIENT_TOOL_FAILURE";
    private static final String RETRY_STRATEGY_QUERY_ADDITIONAL_CONTEXT = "QUERY_ADDITIONAL_CONTEXT";
    private static final String RETRY_STRATEGY_REPLAN_EXECUTION = "REPLAN_EXECUTION";
    private static final String RETRY_STRATEGY_RETRY_TOOL_CALL = "RETRY_TOOL_CALL";

    private final Map<String, ToolExecutor> executorsByKind;

    public ExecutorExecuteNode(List<ToolExecutor> toolExecutors) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    @Override
    public String getName() {
        return "executorExecuteNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        ExecutionPlan executionPlan = state.getContext().get("executionPlan") instanceof ExecutionPlan plan ? plan : null;
        Map<String, Object> payload = state.getContext().get("executorPayload") instanceof Map<?, ?> rawPayload
                ? rawPayload.entrySet().stream().collect(LinkedHashMap::new,
                (map, entry) -> map.put(String.valueOf(entry.getKey()), entry.getValue()),
                Map::putAll)
                : Map.of();

        if (executionPlan == null) {
            return new NodeResult(getName(), NodeStatus.FAILURE, "Execution plan is missing", Map.of("errorCode", 500));
        }

        if (!executionPlan.missingParameters().isEmpty()) {
            return NodeResult.retry(
                    getName(),
                    defaultMessage(executionPlan.retryHint(), "Execution plan still has missing parameters"),
                    RETRY_REASON_MISSING_PARAMETERS,
                    RETRY_STRATEGY_QUERY_ADDITIONAL_CONTEXT,
                    Map.of(
                            "errorCode", 400,
                            "missingParameters", executionPlan.missingParameters(),
                            "action", executionPlan.action(),
                            "executorKind", executionPlan.executorKind()
                    )
            );
        }

        Object complete = payload.get("complete");
        if (!(complete instanceof Boolean done) || !done) {
            return NodeResult.retry(
                    getName(),
                    defaultMessage(executionPlan.retryHint(), "Execution payload is incomplete"),
                    RETRY_REASON_INCOMPLETE_PAYLOAD,
                    RETRY_STRATEGY_REPLAN_EXECUTION,
                    Map.of("errorCode", 409, "action", executionPlan.action(), "executorKind", executionPlan.executorKind())
            );
        }

        String executorKind = executionPlan.executorKind();
        String action = executionPlan.action();
        Map<String, Object> parameters = executionPlan.parameters();
        String executionSummary = executionPlan.executionSummary();
        ToolExecutor toolExecutor = executorsByKind.get(executorKind);

        if (toolExecutor == null) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "No tool executor registered for executorKind=" + executorKind,
                    Map.of("executorKind", executorKind, "action", action, "errorCode", 404)
            );
        }

        Map<String, Object> toolResult = toolExecutor.execute(action, parameters);
        state.getContext().put("executorResult", toolResult);
        state.addObservation("Executor execute: dispatched " + executorKind + "." + action + " with parameters=" + parameters.keySet());

        int httpStatus = readHttpStatus(toolResult);
        String resultStatus = String.valueOf(toolResult.getOrDefault("status", "unknown"));
        if (shouldRetryToolFailure(httpStatus, resultStatus, state.getCurrentLoop())) {
            return NodeResult.retry(
                    getName(),
                    "Executor call returned transient failure for " + executorKind + "." + action,
                    RETRY_REASON_TRANSIENT_TOOL_FAILURE,
                    RETRY_STRATEGY_RETRY_TOOL_CALL,
                    Map.of(
                            "httpStatus", httpStatus,
                            "executorKind", executorKind,
                            "action", action,
                            "toolName", payload.get("toolName"),
                            "result", toolResult
                    )
            );
        }

        if (httpStatus >= 400 || "failed".equalsIgnoreCase(resultStatus)) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Executor call failed for " + executorKind + "." + action,
                    Map.of(
                            "httpStatus", httpStatus,
                            "executorKind", executorKind,
                            "action", action,
                            "toolName", payload.get("toolName"),
                            "result", toolResult
                    )
            );
        }

        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                defaultMessage(executionSummary, "Execution prepared successfully"),
                Map.of(
                        "httpStatus", httpStatus,
                        "executorKind", executorKind,
                        "action", action,
                        "parameterCount", parameters.size(),
                        "requiredParameters", executionPlan.requiredParameters(),
                        "parameterSources", executionPlan.parameterSources(),
                        "toolName", payload.get("toolName"),
                        "result", toolResult
                )
        );
    }

    private boolean shouldRetryToolFailure(int httpStatus, String resultStatus, int currentLoop) {
        if (currentLoop > 0) {
            return false;
        }
        return httpStatus == 408 || httpStatus == 429 || httpStatus >= 500
                || "timeout".equalsIgnoreCase(resultStatus)
                || "retryable".equalsIgnoreCase(resultStatus)
                || "transient_failed".equalsIgnoreCase(resultStatus);
    }

    private int readHttpStatus(Map<String, Object> toolResult) {
        Object raw = toolResult.get("httpStatus");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return 200;
    }

    private String defaultMessage(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
