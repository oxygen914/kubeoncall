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
            return new NodeResult(
                    getName(),
                    NodeStatus.RETRY,
                    defaultMessage(executionPlan.retryHint(), "Execution plan still has missing parameters"),
                    Map.of(
                            "errorCode", 400,
                            "missingParameters", executionPlan.missingParameters(),
                            "action", executionPlan.action()
                    )
            );
        }

        Object complete = payload.get("complete");
        if (!(complete instanceof Boolean done) || !done) {
            return new NodeResult(
                    getName(),
                    NodeStatus.RETRY,
                    defaultMessage(executionPlan.retryHint(), "Execution payload is incomplete"),
                    Map.of("errorCode", 409, "action", executionPlan.action())
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

        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                defaultMessage(executionSummary, "Execution prepared successfully"),
                Map.of(
                        "httpStatus", toolResult.getOrDefault("httpStatus", 200),
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

    private String defaultMessage(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
