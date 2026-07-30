package com.kubeoncall.agent.executor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.agent.node.ExecuteNode;
import com.kubeoncall.agent.planner.PlannerMode;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.evidence.EvidenceItem;
import com.kubeoncall.evidence.EvidenceType;
import com.kubeoncall.skill.SkillExecutionPolicy;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

@Component
public class ExecutorExecuteNode extends ExecuteNode {

    private static final String RETRY_REASON_MISSING_PARAMETERS = "MISSING_PARAMETERS";
    private static final String RETRY_REASON_INCOMPLETE_PAYLOAD = "INCOMPLETE_EXECUTION_PAYLOAD";
    private static final String RETRY_REASON_TRANSIENT_TOOL_FAILURE = "TRANSIENT_TOOL_FAILURE";
    private static final String RETRY_STRATEGY_QUERY_ADDITIONAL_CONTEXT = "QUERY_ADDITIONAL_CONTEXT";
    private static final String RETRY_STRATEGY_REPLAN_EXECUTION = "REPLAN_EXECUTION";
    private static final String RETRY_STRATEGY_RETRY_TOOL_CALL = "RETRY_TOOL_CALL";

    private final Map<String, ToolExecutor> executorsByKind;
    private final OperationClosureService closureService;

    public ExecutorExecuteNode(List<ToolExecutor> toolExecutors) {
        this(toolExecutors, null);
    }

    @Autowired
    public ExecutorExecuteNode(List<ToolExecutor> toolExecutors, OperationClosureService closureService) {
        this.executorsByKind = toolExecutors.stream()
                .collect(Collectors.toMap(
                        ToolExecutor::getExecutorKind, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        this.closureService = closureService;
    }

    @Override
    public String getName() {
        return "executorExecuteNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        ExecutionPlan executionPlan =
                state.getContext().get("executionPlan") instanceof ExecutionPlan plan ? plan : null;
        Map<String, Object> payload = state.getContext().get("executorPayload") instanceof Map<?, ?> rawPayload
                ? rawPayload.entrySet().stream()
                        .collect(
                                LinkedHashMap::new,
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
                            "executorKind", executionPlan.executorKind()));
        }

        Object complete = payload.get("complete");
        if (!(complete instanceof Boolean done) || !done) {
            return NodeResult.retry(
                    getName(),
                    defaultMessage(executionPlan.retryHint(), "Execution payload is incomplete"),
                    RETRY_REASON_INCOMPLETE_PAYLOAD,
                    RETRY_STRATEGY_REPLAN_EXECUTION,
                    Map.of(
                            "errorCode",
                            409,
                            "action",
                            executionPlan.action(),
                            "executorKind",
                            executionPlan.executorKind()));
        }

        String executorKind = executionPlan.executorKind();
        String action = executionPlan.action();
        Map<String, Object> parameters = new LinkedHashMap<>(executionPlan.parameters());
        String executionSummary = executionPlan.executionSummary();
        ToolExecutor toolExecutor = executorsByKind.get(executorKind);

        if (toolExecutor == null) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "No tool executor registered for executorKind=" + executorKind,
                    Map.of("executorKind", executorKind, "action", action, "errorCode", 404));
        }

        ToolDefinition toolDefinition =
                state.getContext().get("executorToolDefinition") instanceof ToolDefinition definition
                        ? definition
                        : null;
        if (toolDefinition == null) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Executor tool definition is missing",
                    Map.of("executorKind", executorKind, "action", action, "errorCode", 409));
        }
        String plannedToolName = executorKind + "." + action;
        if (!plannedToolName.equals(toolDefinition.name()) || !executorKind.equals(toolDefinition.executorKind())) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Execution plan and executor tool definition do not match",
                    Map.of(
                            "reason",
                            "EXECUTOR_TOOL_DEFINITION_MISMATCH",
                            "plannedTool",
                            plannedToolName,
                            "definedTool",
                            toolDefinition.name(),
                            "errorCode",
                            409));
        }
        NodeResult skillViolation = skillViolation(state, toolDefinition, plannedToolName);
        if (skillViolation != null) {
            return skillViolation;
        }
        if (!toolDefinition.readOnly() && Boolean.TRUE.equals(state.getContext().get("compatibilityReadOnly"))) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "The synchronous Ask compatibility endpoint cannot execute mutating tools",
                    Map.of("executorKind", executorKind, "action", action, "errorCode", 403));
        }
        PlannerMode plannerMode = PlannerMode.runtime(state.getContext().get("plannerMode"));
        if (!toolDefinition.readOnly() && !plannerMode.mutationCandidateAllowed()) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Planner mode " + plannerMode + " cannot execute mutating tools",
                    Map.of(
                            "executorKind",
                            executorKind,
                            "action",
                            action,
                            "plannerMode",
                            plannerMode.name(),
                            "errorCode",
                            403));
        }

        Map<String, Object> evidenceResult = evidenceBackedReadResult(state, toolDefinition, action);
        if (!evidenceResult.isEmpty()) {
            state.getContext().put("executorResult", evidenceResult);
            state.addObservation("Executor execute: satisfied " + executorKind + "." + action
                    + " from the unified evidence snapshot");
            return new NodeResult(
                    getName(),
                    NodeStatus.SUCCESS,
                    defaultMessage(executionSummary, "Read-only query satisfied from unified evidence"),
                    Map.of(
                            "httpStatus",
                            200,
                            "executorKind",
                            executorKind,
                            "action",
                            action,
                            "toolName",
                            payload.get("toolName"),
                            "evidenceBacked",
                            true,
                            "result",
                            evidenceResult));
        }

        if (closureService != null) {
            OperationClosureService.Preparation preparation =
                    closureService.prepare(state, executionPlan, toolDefinition);
            if (!preparation.ready()) {
                state.getContext().put(OperationClosureService.CONTEXT_KEY, preparation.details());
                return new NodeResult(
                        getName(),
                        NodeStatus.FAILURE,
                        preparation.reason(),
                        Map.of(
                                "errorCode",
                                409,
                                "executorKind",
                                executorKind,
                                "action",
                                action,
                                "closure",
                                preparation.details()));
            }
            if (preparation.required()) {
                parameters.put("operationId", operationId(state, executorKind, action));
            }
        }

        Map<String, Object> toolResult = toolExecutor.execute(action, parameters);
        state.getContext().put("executorResult", toolResult);
        state.addObservation("Executor execute: dispatched " + executorKind + "." + action + " with parameters="
                + parameters.keySet());

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
                            "result", toolResult));
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
                            "result", toolResult));
        }

        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                defaultMessage(executionSummary, "Execution prepared successfully"),
                Map.of(
                        "httpStatus",
                        httpStatus,
                        "executorKind",
                        executorKind,
                        "action",
                        action,
                        "parameterCount",
                        parameters.size(),
                        "requiredParameters",
                        executionPlan.requiredParameters(),
                        "parameterSources",
                        executionPlan.parameterSources(),
                        "toolName",
                        payload.get("toolName"),
                        "result",
                        toolResult));
    }

    private NodeResult skillViolation(GraphState state, ToolDefinition toolDefinition, String plannedToolName) {
        SkillExecutionPolicy.ToolAccess toolAccess = SkillExecutionPolicy.toolAccess(state.getContext());
        if (toolAccess.restricted() && !toolAccess.allows(plannedToolName)) {
            Map<String, Object> details = Map.of(
                    "reason",
                    "SKILL_TOOL_NOT_ALLOWED",
                    "plannedTool",
                    plannedToolName,
                    "allowedTools",
                    toolAccess.allowedTools(),
                    "errorCode",
                    403);
            state.getContext().put("skillToolWhitelistViolation", details);
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Activated skill does not allow executor tool " + plannedToolName,
                    details);
        }
        Task task = state.getCurrentTask();
        RiskLevel maxRisk = SkillExecutionPolicy.maxRisk(state.getContext());
        if (toolAccess.restricted() && maxRisk == null) {
            Map<String, Object> details = Map.of("reason", "SKILL_MAX_RISK_MISSING", "errorCode", 403);
            state.getContext().put("skillRiskViolation", details);
            return new NodeResult(
                    getName(), NodeStatus.FAILURE, "Activated skill maxRisk is missing or invalid", details);
        }
        if (toolAccess.restricted() && (task == null || task.riskLevel() == null)) {
            Map<String, Object> details = Map.of("reason", "TASK_RISK_MISSING", "errorCode", 403);
            state.getContext().put("skillRiskViolation", details);
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Task risk is required when an activated skill is enforced",
                    details);
        }
        if (task != null && SkillExecutionPolicy.exceedsMaxRisk(task.riskLevel(), maxRisk)) {
            Map<String, Object> details = Map.of(
                    "reason",
                    "SKILL_MAX_RISK_EXCEEDED",
                    "taskRisk",
                    task.riskLevel().name(),
                    "maxRisk",
                    maxRisk.name(),
                    "errorCode",
                    403);
            state.getContext().put("skillRiskViolation", details);
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Activated skill maxRisk " + maxRisk + " is below task risk " + task.riskLevel(),
                    details);
        }
        return null;
    }

    private Map<String, Object> evidenceBackedReadResult(GraphState state, ToolDefinition definition, String action) {
        if (!definition.readOnly()) {
            return Map.of();
        }
        List<EvidenceType> acceptedTypes =
                switch (action) {
                    case "queryLogs" -> List.of(EvidenceType.POD_LOG, EvidenceType.K8S_EVENT);
                    case "queryMetricsContext" -> List.of(EvidenceType.METRIC, EvidenceType.RESOURCE_STATE);
                    default -> List.of();
                };
        if (acceptedTypes.isEmpty() || !(state.getContext().get("evidenceItems") instanceof List<?> candidates)) {
            return Map.of();
        }
        List<EvidenceItem> evidence = candidates.stream()
                .filter(EvidenceItem.class::isInstance)
                .map(EvidenceItem.class::cast)
                .filter(EvidenceItem::succeeded)
                .filter(item -> acceptedTypes.contains(item.type()))
                .limit(20)
                .toList();
        if (evidence.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(
                "evidenceIds", evidence.stream().map(EvidenceItem::evidenceId).toList());
        response.put(
                "sources",
                evidence.stream().map(EvidenceItem::source).distinct().toList());
        response.put(
                "summaries",
                evidence.stream()
                        .map(EvidenceItem::summary)
                        .filter(summary -> summary != null && !summary.isBlank())
                        .toList());
        response.put("observedCount", evidence.size());
        return Map.of("status", "success", "httpStatus", 200, "source", "unified-evidence", "response", response);
    }

    private boolean shouldRetryToolFailure(int httpStatus, String resultStatus, int currentLoop) {
        if (currentLoop > 0) {
            return false;
        }
        return httpStatus == 408
                || httpStatus == 429
                || httpStatus >= 500
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

    private String operationId(GraphState state, String executorKind, String action) {
        String executionId = state.getExecutionId() == null ? "unassigned" : state.getExecutionId();
        String taskId =
                state.getCurrentTask() == null ? "task" : state.getCurrentTask().taskId();
        return executionId + ":" + taskId + ":" + executorKind + "." + action;
    }
}
