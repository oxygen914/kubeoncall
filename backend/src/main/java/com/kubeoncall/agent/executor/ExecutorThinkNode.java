package com.kubeoncall.agent.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.agent.node.ThinkNode;
import com.kubeoncall.agent.sandbox.SandboxAgentRunSubmissionService;
import com.kubeoncall.agent.sandbox.SandboxRoutingPolicy;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillExecutionPolicy;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;

@Component
public class ExecutorThinkNode extends ThinkNode {

    private static final String RETRY_REASON_MISSING_PARAMETERS = "MISSING_PARAMETERS";
    private static final String RETRY_STRATEGY_QUERY_ADDITIONAL_CONTEXT = "QUERY_ADDITIONAL_CONTEXT";
    private static final String FAILURE_REASON_SKILL_TOOL_NOT_ALLOWED = "SKILL_TOOL_NOT_ALLOWED";
    private static final String FAILURE_REASON_SKILL_TOOL_WHITELIST_EMPTY = "SKILL_TOOL_WHITELIST_EMPTY";
    private static final String FAILURE_REASON_SKILL_MAX_RISK_MISSING = "SKILL_MAX_RISK_MISSING";
    private static final String FAILURE_REASON_TASK_RISK_MISSING = "TASK_RISK_MISSING";
    private static final String FAILURE_REASON_SKILL_MAX_RISK_EXCEEDED = "SKILL_MAX_RISK_EXCEEDED";

    private final AgentToolCatalog agentToolCatalog;
    private final ExecutorPlanFactory planFactory;
    private final KubeOnCallMetricsService metricsService;
    private final SandboxRoutingPolicy sandboxRoutingPolicy;
    private final SandboxAgentRunSubmissionService sandboxSubmissionService;

    public ExecutorThinkNode(AgentToolCatalog agentToolCatalog, ExecutorPlanFactory planFactory) {
        this(agentToolCatalog, planFactory, null, null, null);
    }

    public ExecutorThinkNode(
            AgentToolCatalog agentToolCatalog,
            ExecutorPlanFactory planFactory,
            KubeOnCallMetricsService metricsService) {
        this(agentToolCatalog, planFactory, metricsService, null, null);
    }

    @Autowired
    public ExecutorThinkNode(
            AgentToolCatalog agentToolCatalog,
            ExecutorPlanFactory planFactory,
            KubeOnCallMetricsService metricsService,
            SandboxRoutingPolicy sandboxRoutingPolicy,
            ObjectProvider<SandboxAgentRunSubmissionService> sandboxSubmissionServiceProvider) {
        this.agentToolCatalog = agentToolCatalog;
        this.planFactory = planFactory;
        this.metricsService = metricsService;
        this.sandboxRoutingPolicy = sandboxRoutingPolicy;
        this.sandboxSubmissionService =
                sandboxSubmissionServiceProvider == null ? null : sandboxSubmissionServiceProvider.getIfAvailable();
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
        SkillExecutionPolicy.ToolAccess toolAccess = SkillExecutionPolicy.toolAccess(state.getContext());
        if (toolAccess.restricted() && toolAccess.allowedTools().isEmpty()) {
            return emptySkillToolWhitelist(state);
        }
        RiskLevel skillMaxRisk = SkillExecutionPolicy.maxRisk(state.getContext());
        if (toolAccess.restricted() && skillMaxRisk == null) {
            return missingSkillMaxRisk(state);
        }
        if (toolAccess.restricted() && task.riskLevel() == null) {
            return missingTaskRisk(state);
        }
        if (SkillExecutionPolicy.exceedsMaxRisk(task.riskLevel(), skillMaxRisk)) {
            return skillRiskViolation(state, task, skillMaxRisk);
        }

        if (sandboxRoutingPolicy != null) {
            SandboxRoutingPolicy.Decision decision =
                    sandboxRoutingPolicy.decide(task, state.getUserRequest(), state.getContext());
            if (decision.routed()) {
                if (sandboxSubmissionService == null) {
                    return new NodeResult(
                            getName(), NodeStatus.FAILURE, "Sandbox routing service is unavailable", Map.of());
                }
                SandboxAgentRunSubmissionService.Submission submission =
                        sandboxSubmissionService.submit(state, task, decision);
                if (!submission.submitted()) {
                    return new NodeResult(
                            getName(),
                            NodeStatus.FAILURE,
                            "Sandbox route was not submitted",
                            Map.of("reason", submission.reason()));
                }
                Map<String, Object> route = Map.of(
                        "mode",
                        decision.mode().name(),
                        "reason",
                        decision.reason(),
                        "taskId",
                        task.taskId(),
                        "runId",
                        submission.runId());
                state.getContext().put("sandboxRoute", route);
                state.addObservation("Executor: sandbox route selected mode=" + decision.mode());
                return new NodeResult(
                        getName(),
                        NodeStatus.WAITING,
                        "Sandbox route selected; waiting for durable Sandbox Run creation",
                        route);
            }
        }

        String executorKind = planFactory.executorKind(task.taskType());
        String action = planFactory.action(task.taskType());
        List<String> toolWhitelist = planFactory.toolWhitelist(state);
        ToolDefinition toolDefinition = toolWhitelist.isEmpty()
                ? agentToolCatalog.findExecutorTool(executorKind, action)
                : agentToolCatalog.findExecutorTool(executorKind, action, toolWhitelist);
        if (toolDefinition == null && !toolWhitelist.isEmpty()) {
            return skillToolViolation(state, executorKind, action, toolWhitelist);
        }
        if (toolDefinition == null) {
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "No executor tool registered for " + executorKind + "." + action,
                    Map.of());
        }

        Map<String, Object> parameters = new LinkedHashMap<>(task.parameters());
        List<String> requiredParameters = new ArrayList<>(toolDefinition.requiredParameters());
        List<String> missingParameters = planFactory.missingParameters(parameters, requiredParameters);
        if (!missingParameters.isEmpty() && state.getCurrentLoop() == 0) {
            Map<String, Object> supplemental = planFactory.supplementalSignals(state);
            Map<String, String> supplementalSources = planFactory.supplementalSignalSources(state);
            if (!supplemental.isEmpty()) {
                planFactory.mergeSupplementalParameters(parameters, missingParameters, supplemental);
                missingParameters = planFactory.missingParameters(parameters, requiredParameters);
                if (missingParameters.isEmpty()) {
                    state.addObservation("Executor: completed missing parameters from planner supplemental signals");
                }
            }
            if (!missingParameters.isEmpty()) {
                return missingParameterRetry(
                        state,
                        executorKind,
                        action,
                        parameters,
                        requiredParameters,
                        missingParameters,
                        supplementalSources,
                        toolDefinition);
            }
        }

        List<String> defaultedParameters = List.of();
        if (!missingParameters.isEmpty() && state.getCurrentLoop() > 0) {
            defaultedParameters = new ArrayList<>(missingParameters);
            planFactory.applyDefaults(parameters, missingParameters);
            missingParameters = planFactory.missingParameters(parameters, requiredParameters);
        }
        Map<String, String> parameterSources = planFactory.parameterSources(
                parameters, state.getCurrentLoop(), defaultedParameters, planFactory.supplementalSignalSources(state));
        String executionSummary =
                planFactory.executionSummary(executorKind, action, task.target(), parameters, toolDefinition);
        ExecutionPlan plan = new ExecutionPlan(
                executorKind,
                action,
                parameters,
                requiredParameters,
                missingParameters,
                parameterSources,
                executionSummary,
                null);

        state.getContext().put("executionPlan", plan);
        state.getContext()
                .put("executorPayload", planFactory.payload(executorKind, action, parameters, true, toolDefinition));
        state.getContext().put("executorToolDefinition", toolDefinition);
        if (!toolWhitelist.isEmpty()) {
            state.getContext().put("activatedSkillToolWhitelist", toolWhitelist);
        }
        state.addObservation("Executor: kind=" + executorKind + ", action=" + action + ", tool=" + toolDefinition.name()
                + ", target=" + task.target());
        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                "Executor generated execution plan",
                Map.of(
                        "executorKind",
                        executorKind,
                        "action",
                        action,
                        "toolName",
                        toolDefinition.name(),
                        "parameterCount",
                        parameters.size()));
    }

    private NodeResult skillRiskViolation(GraphState state, Task task, RiskLevel skillMaxRisk) {
        Map<String, Object> violation = Map.of(
                "reason", FAILURE_REASON_SKILL_MAX_RISK_EXCEEDED,
                "taskRisk", task.riskLevel().name(),
                "maxRisk", skillMaxRisk.name(),
                "taskType", task.taskType().name());
        state.getContext().put("skillRiskViolation", violation);
        if (metricsService != null) {
            metricsService.recordSkillGovernance("max_risk_violation", "rejected");
        }
        state.addObservation("Executor: blocked task risk " + task.riskLevel()
                + " because the activated skill maxRisk is " + skillMaxRisk);
        return new NodeResult(
                getName(),
                NodeStatus.FAILURE,
                "Activated skill maxRisk " + skillMaxRisk + " is below task risk " + task.riskLevel(),
                violation);
    }

    private NodeResult emptySkillToolWhitelist(GraphState state) {
        Map<String, Object> violation =
                Map.of("reason", FAILURE_REASON_SKILL_TOOL_WHITELIST_EMPTY, "allowedTools", List.of());
        state.getContext().put("skillToolWhitelistViolation", violation);
        if (metricsService != null) {
            metricsService.recordSkillGovernance("whitelist_empty", "rejected");
        }
        state.addObservation("Executor: blocked task because the activated skill tool whitelist is empty");
        return new NodeResult(getName(), NodeStatus.FAILURE, "Activated skill has an empty tool whitelist", violation);
    }

    private NodeResult missingSkillMaxRisk(GraphState state) {
        Map<String, Object> violation = Map.of("reason", FAILURE_REASON_SKILL_MAX_RISK_MISSING);
        state.getContext().put("skillRiskViolation", violation);
        if (metricsService != null) {
            metricsService.recordSkillGovernance("max_risk_missing", "rejected");
        }
        state.addObservation("Executor: blocked task because the activated skill maxRisk is missing");
        return new NodeResult(
                getName(), NodeStatus.FAILURE, "Activated skill maxRisk is missing or invalid", violation);
    }

    private NodeResult missingTaskRisk(GraphState state) {
        Map<String, Object> violation = Map.of("reason", FAILURE_REASON_TASK_RISK_MISSING);
        state.getContext().put("skillRiskViolation", violation);
        if (metricsService != null) {
            metricsService.recordSkillGovernance("task_risk_missing", "rejected");
        }
        state.addObservation("Executor: blocked task because its risk level is missing");
        return new NodeResult(
                getName(), NodeStatus.FAILURE, "Task risk is required when an activated skill is enforced", violation);
    }

    private NodeResult skillToolViolation(
            GraphState state, String executorKind, String action, List<String> toolWhitelist) {
        String toolName = executorKind + "." + action;
        Map<String, Object> violation = Map.of(
                "reason", FAILURE_REASON_SKILL_TOOL_NOT_ALLOWED,
                "plannedTool", toolName,
                "allowedTools", toolWhitelist);
        state.getContext().put("skillToolWhitelistViolation", violation);
        if (metricsService != null) {
            metricsService.recordSkillGovernance("whitelist_violation", "rejected");
        }
        state.addObservation(
                "Executor: blocked tool " + toolName + " because it is not allowed by the activated skill");
        return new NodeResult(
                getName(), NodeStatus.FAILURE, "Activated skill does not allow executor tool " + toolName, violation);
    }

    private NodeResult missingParameterRetry(
            GraphState state,
            String executorKind,
            String action,
            Map<String, Object> parameters,
            List<String> requiredParameters,
            List<String> missingParameters,
            Map<String, String> supplementalSources,
            ToolDefinition toolDefinition) {
        String retryHint = "Missing required parameters: " + String.join(", ", missingParameters)
                + ". Querying additional context.";
        ExecutionPlan plan = new ExecutionPlan(
                executorKind,
                action,
                parameters,
                requiredParameters,
                missingParameters,
                planFactory.parameterSources(parameters, state.getCurrentLoop(), List.of(), supplementalSources),
                "Execution plan incomplete, retry required",
                retryHint);
        state.getContext().put("executionPlan", plan);
        state.getContext()
                .put("executorPayload", planFactory.payload(executorKind, action, parameters, false, toolDefinition));
        state.getContext().put("executorToolDefinition", toolDefinition);
        return NodeResult.retry(
                getName(),
                retryHint,
                RETRY_REASON_MISSING_PARAMETERS,
                RETRY_STRATEGY_QUERY_ADDITIONAL_CONTEXT,
                Map.of("missingParameters", missingParameters, "action", action, "executorKind", executorKind));
    }
}
