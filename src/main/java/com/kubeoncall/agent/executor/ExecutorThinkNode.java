package com.kubeoncall.agent.executor;

import com.kubeoncall.agent.node.ThinkNode;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExecutorThinkNode extends ThinkNode {

    private final AgentToolCatalog agentToolCatalog;

    public ExecutorThinkNode(AgentToolCatalog agentToolCatalog) {
        this.agentToolCatalog = agentToolCatalog;
    }

    @Override
    public String getName() {
        return "executorThinkNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        Task task = state.getCurrentTask();
        if (task == null) {
            return new NodeResult(getName(), NodeStatus.FAILURE, "No task available for execution", Map.of());
        }

        String executorKind = inferExecutorKind(task.taskType());
        String action = inferAction(task.taskType());
        ToolDefinition toolDefinition = agentToolCatalog.findExecutorTool(executorKind, action);
        if (toolDefinition == null) {
            return new NodeResult(getName(), NodeStatus.FAILURE, "No executor tool registered for " + executorKind + "." + action, Map.of());
        }

        Map<String, Object> parameters = new LinkedHashMap<>(task.parameters());
        List<String> requiredParameters = new ArrayList<>(toolDefinition.requiredParameters());
        List<String> missingParameters = identifyMissingParameters(parameters, requiredParameters);
        Map<String, String> parameterSources = buildParameterSources(parameters, state.getCurrentLoop());

        if (!missingParameters.isEmpty() && state.getCurrentLoop() == 0) {
            String retryHint = "Missing required parameters: " + String.join(", ", missingParameters) + ". Will apply defaults on retry.";
            ExecutionPlan plan = new ExecutionPlan(
                    executorKind,
                    action,
                    parameters,
                    requiredParameters,
                    missingParameters,
                    parameterSources,
                    "Execution plan incomplete, retry required",
                    retryHint
            );
            state.getContext().put("executionPlan", plan);
            state.getContext().put("executorPayload", buildPayload(executorKind, action, parameters, false, toolDefinition));
            state.getContext().put("executorToolDefinition", toolDefinition);
            return new NodeResult(getName(), NodeStatus.RETRY, retryHint, Map.of("missingParameters", missingParameters));
        }

        if (!missingParameters.isEmpty() && state.getCurrentLoop() > 0) {
            applyDefaults(parameters, missingParameters);
            parameterSources = buildParameterSources(parameters, state.getCurrentLoop());
            missingParameters = List.of();
        }

        String executionSummary = buildExecutionSummary(executorKind, action, task.target(), parameters, toolDefinition);
        ExecutionPlan plan = new ExecutionPlan(
                executorKind,
                action,
                parameters,
                requiredParameters,
                missingParameters,
                parameterSources,
                executionSummary,
                null
        );

        state.getContext().put("executionPlan", plan);
        state.getContext().put("executorPayload", buildPayload(executorKind, action, parameters, true, toolDefinition));
        state.getContext().put("executorToolDefinition", toolDefinition);
        state.addObservation("Executor: kind=" + executorKind + ", action=" + action + ", tool=" + toolDefinition.name() + ", target=" + task.target());

        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                "Executor generated execution plan",
                Map.of(
                        "executorKind", executorKind,
                        "action", action,
                        "toolName", toolDefinition.name(),
                        "parameterCount", parameters.size()
                )
        );
    }

    private String inferExecutorKind(TaskType taskType) {
        return switch (taskType) {
            case QUERY_LOGS, QUERY_METRICS, RESTART_SERVICE, SCALE_WORKLOAD, PATCH_CONFIG -> "kubernetes";
            case EXECUTE_SCRIPT -> "device";
            case CLEAN_DATA -> "database";
        };
    }

    private String inferAction(TaskType taskType) {
        return switch (taskType) {
            case QUERY_LOGS -> "queryLogs";
            case QUERY_METRICS -> "queryMetricsContext";
            case RESTART_SERVICE -> "rolloutRestart";
            case SCALE_WORKLOAD -> "scaleWorkload";
            case PATCH_CONFIG -> "patchConfig";
            case EXECUTE_SCRIPT -> "executeScript";
            case CLEAN_DATA -> "cleanData";
        };
    }

    private List<String> identifyMissingParameters(Map<String, Object> parameters, List<String> required) {
        List<String> missing = new ArrayList<>();
        for (String param : required) {
            if (!parameters.containsKey(param) || parameters.get(param) == null) {
                missing.add(param);
            }
        }
        return missing;
    }

    private void applyDefaults(Map<String, Object> parameters, List<String> missing) {
        for (String param : missing) {
            switch (param) {
                case "namespace" -> parameters.put("namespace", "default");
                case "lookbackMinutes" -> parameters.put("lookbackMinutes", 15);
                case "windowMinutes" -> parameters.put("windowMinutes", 10);
                case "rolloutStrategy" -> parameters.put("rolloutStrategy", "rolling");
                case "replicas" -> parameters.put("replicas", 3);
                case "readonly" -> parameters.put("readonly", true);
                case "keyword" -> parameters.put("keyword", "error");
                case "metricNames" -> parameters.put("metricNames", List.of("cpu_usage"));
                case "target" -> parameters.put("target", "default-scope");
            }
        }
    }

    private Map<String, String> buildParameterSources(Map<String, Object> parameters, int currentLoop) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : parameters.keySet()) {
            if (currentLoop > 0 && (key.equals("namespace") || key.equals("lookbackMinutes") || key.equals("windowMinutes") || key.equals("target"))) {
                sources.put(key, "default_applied_on_retry");
            } else {
                sources.put(key, "from_planner");
            }
        }
        return sources;
    }

    private String buildExecutionSummary(String executorKind, String action, String target, Map<String, Object> parameters, ToolDefinition toolDefinition) {
        return String.format("Execute %s.%s via %s on target=%s with %d parameters", executorKind, action, toolDefinition.name(), target, parameters.size());
    }

    private Map<String, Object> buildPayload(String executorKind, String action, Map<String, Object> parameters, boolean complete, ToolDefinition toolDefinition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executorKind", executorKind);
        payload.put("action", action);
        payload.put("toolName", toolDefinition.name());
        payload.put("readOnly", toolDefinition.readOnly());
        payload.put("requiresApproval", toolDefinition.requiresApproval());
        payload.put("parameters", parameters);
        payload.put("complete", complete);
        payload.put("apiVersion", "v1");
        return payload;
    }
}
