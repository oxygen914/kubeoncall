package com.kubeoncall.agent.sandbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

/** Performs the one fixed, read-only production recheck required before verifier review. */
@Service
public class SandboxProductionStateRecheckService {

    private static final String EXECUTOR_KIND = "kubernetes";
    private static final String ACTION = "describeWorkload";
    private static final String TOOL_NAME = EXECUTOR_KIND + "." + ACTION;

    private final List<ToolExecutor> executors;

    public SandboxProductionStateRecheckService(List<ToolExecutor> executors) {
        this.executors = List.copyOf(executors);
    }

    public Recheck recheck(GraphState state) {
        Task task = state == null ? null : state.getCurrentTask();
        ToolExecutor executor = executors.stream()
                .filter(candidate -> EXECUTOR_KIND.equals(candidate.getExecutorKind()))
                .findFirst()
                .orElse(null);
        if (task == null || executor == null || !supportsSafeRecheck(executor)) {
            return record(state, "UNAVAILABLE", null);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        Object namespace = task.parameters() == null ? null : task.parameters().get("namespace");
        parameters.put(
                "namespace", namespace == null || String.valueOf(namespace).isBlank() ? "default" : namespace);
        parameters.put("target", task.target() == null ? "" : task.target());
        Map<String, Object> response = executor.execute(ACTION, parameters);
        int httpStatus = response.get("httpStatus") instanceof Number number ? number.intValue() : 200;
        String outcome = String.valueOf(response.getOrDefault("status", "ok"));
        return record(
                state, httpStatus >= 400 || "failed".equalsIgnoreCase(outcome) ? "FAILED" : "CURRENT", httpStatus);
    }

    private static boolean supportsSafeRecheck(ToolExecutor executor) {
        return executor.supportedTools().stream()
                .filter(tool -> TOOL_NAME.equals(tool.name()))
                .anyMatch(ToolDefinition::readOnly);
    }

    private static Recheck record(GraphState state, String status, Integer httpStatus) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("tool", TOOL_NAME);
        summary.put("observedAt", Instant.now());
        if (httpStatus != null) {
            summary.put("httpStatus", httpStatus);
        }
        if (state != null) {
            state.getContext().put("productionRecheck", summary);
        }
        return new Recheck("CURRENT".equals(status), Map.copyOf(summary));
    }

    public record Recheck(boolean current, Map<String, Object> summary) {}
}
