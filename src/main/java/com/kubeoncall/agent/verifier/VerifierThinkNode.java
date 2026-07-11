package com.kubeoncall.agent.verifier;

import com.kubeoncall.agent.node.ThinkNode;
import com.kubeoncall.domain.graph.ExecutionPlan;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Component
public class VerifierThinkNode extends ThinkNode {

    private final AgentToolCatalog agentToolCatalog;

    public VerifierThinkNode(AgentToolCatalog agentToolCatalog) {
        this.agentToolCatalog = agentToolCatalog;
    }

    @Override
    public String getName() {
        return "verifierThinkNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        Task task = state.getCurrentTask();
        if (task == null) {
            return new NodeResult(getName(), NodeStatus.FAILURE, "No task available for verification", Map.of());
        }
        if (task.sopReference() == null) {
            return new NodeResult(getName(), NodeStatus.FAILURE, "Task missing SOP reference", Map.of("taskId", task.taskId()));
        }

        ExecutionPlan executionPlan = state.getContext().get("executionPlan") instanceof ExecutionPlan plan ? plan : null;
        Evaluation evaluation = evaluate(task, executionPlan, state.getContext());
        state.getContext().put("verifierDecision", evaluation.decision());
        state.getContext().put("verifierRiskReasons", evaluation.reasons());
        state.getContext().put("verifierTool", evaluation.details().get("toolName"));
        state.addObservation("Verifier decision=" + evaluation.decision() + ", reasons=" + evaluation.reasons());

        if ("REJECT".equals(evaluation.decision())) {
            state.addApprovalAudit("Verifier blocked task before approval, taskId=" + task.taskId());
            state.setFinalApprovalDecision(com.kubeoncall.domain.approval.ApprovalDecision.REJECTED);
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Task blocked by verifier: " + String.join("; ", evaluation.reasons()),
                    evaluation.details()
            );
        }

        if ("APPROVAL_REQUIRED".equals(evaluation.decision())) {
            return new NodeResult(
                    getName(),
                    NodeStatus.SUCCESS,
                    "Verifier requires approval before execution",
                    evaluation.details()
            );
        }

        return new NodeResult(getName(), NodeStatus.SUCCESS, "Task passed verification", evaluation.details());
    }

    private Evaluation evaluate(Task task, ExecutionPlan executionPlan, Map<String, Object> context) {
        List<String> reasons = new ArrayList<>();
        String description = task.description() == null ? "" : task.description().toLowerCase(Locale.ROOT);
        String target = task.target() == null ? "" : task.target().toLowerCase(Locale.ROOT);
        ToolDefinition toolDefinition = resolveToolDefinition(task, executionPlan);
        RiskLevel activatedSkillMaxRisk = readActivatedSkillMaxRisk(context);

        if (toolDefinition == null) {
            reasons.add("No executor tool is registered for the planned task action");
            return new Evaluation("REJECT", reasons, detailMap(task, executionPlan, null, reasons, context));
        }

        if (!toolDefinition.supportedTaskTypes().contains(task.taskType())) {
            reasons.add("Tool " + toolDefinition.name() + " does not support task type " + task.taskType());
        }
        if (task.taskType() == TaskType.CLEAN_DATA) {
            reasons.add("Data cleanup is treated as a destructive red-line action");
        }
        if (task.taskType() == TaskType.EXECUTE_SCRIPT && !Boolean.TRUE.equals(task.parameters().get("readonly"))) {
            reasons.add("Script execution without readonly guard is not allowed");
        }
        if (description.contains("生产") || description.contains("prod") || target.contains("prod")) {
            reasons.add("Target appears to be production scoped");
        }
        if (task.riskLevel().ordinal() >= RiskLevel.CRITICAL.ordinal()) {
            reasons.add("Risk level is critical");
        }
        if (toolDefinition.name().equals("database.cleanData")) {
            reasons.add("database.cleanData is blocked by policy until explicit break-glass support exists");
        }

        if (!reasons.isEmpty() && (task.taskType() == TaskType.CLEAN_DATA
                || task.taskType() == TaskType.EXECUTE_SCRIPT
                || task.riskLevel().ordinal() >= RiskLevel.CRITICAL.ordinal()
                || toolDefinition.name().equals("database.cleanData"))) {
            return new Evaluation("REJECT", reasons, detailMap(task, executionPlan, toolDefinition, reasons, context));
        }

        if (activatedSkillMaxRisk != null && task.riskLevel().ordinal() > activatedSkillMaxRisk.ordinal()) {
            reasons.add("Activated skill maxRisk " + activatedSkillMaxRisk + " is below task risk " + task.riskLevel());
        }
        if (task.riskLevel().ordinal() >= RiskLevel.HIGH.ordinal()) {
            reasons.add("Risk level requires human approval");
        }
        if (!toolDefinition.readOnly() && toolDefinition.requiresApproval()) {
            reasons.add("Selected executor tool mutates external state and requires approval");
        }
        if (task.taskType() == TaskType.RESTART_SERVICE || task.taskType() == TaskType.PATCH_CONFIG || task.taskType() == TaskType.SCALE_WORKLOAD) {
            reasons.add("Change action requires manual confirmation");
        }
        if (target.contains("core") || target.contains("payment") || target.contains("master")) {
            reasons.add("Target is a critical service");
        }

        if (!reasons.isEmpty()) {
            return new Evaluation("APPROVAL_REQUIRED", reasons, detailMap(task, executionPlan, toolDefinition, reasons, context));
        }

        List<String> allowReasons = List.of("Task is within automatic execution guardrails");
        return new Evaluation("ALLOW", allowReasons, detailMap(task, executionPlan, toolDefinition, allowReasons, context));
    }

    private ToolDefinition resolveToolDefinition(Task task, ExecutionPlan executionPlan) {
        if (executionPlan != null) {
            ToolDefinition definition = agentToolCatalog.findExecutorTool(executionPlan.executorKind(), executionPlan.action());
            if (definition != null) {
                return definition;
            }
        }
        return agentToolCatalog.executorTools().stream()
                .filter(tool -> tool.supportedTaskTypes().contains(task.taskType()))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> detailMap(Task task,
                                          ExecutionPlan executionPlan,
                                          ToolDefinition toolDefinition,
                                          List<String> reasons,
                                          Map<String, Object> context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", task.taskId());
        details.put("taskType", task.taskType() == null ? null : task.taskType().name());
        details.put("riskLevel", task.riskLevel() == null ? null : task.riskLevel().name());
        details.put("target", task.target());
        details.put("riskReasons", reasons);
        details.put("toolName", toolDefinition == null ? null : toolDefinition.name());
        details.put("executorKind", toolDefinition == null ? null : toolDefinition.executorKind());
        details.put("readOnly", toolDefinition != null && toolDefinition.readOnly());
        details.put("requiresApproval", toolDefinition != null && toolDefinition.requiresApproval());
        details.put("supportedTaskTypes", toolDefinition == null ? List.of() : toolDefinition.supportedTaskTypes());
        if (executionPlan != null) {
            details.put("plannedAction", executionPlan.action());
            details.put("plannedExecutorKind", executionPlan.executorKind());
        }
        if (context != null) {
            putIfPresent(details, context, "activatedSkills");
            putIfPresent(details, context, "activatedSkillIds");
            putIfPresent(details, context, "activatedSkillMaxRisk");
            putIfPresent(details, context, "activatedSkillToolWhitelist");
            putIfPresent(details, context, "skillToolWhitelistViolation");
        }
        return details;
    }

    private RiskLevel readActivatedSkillMaxRisk(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object value = context.get("activatedSkillMaxRisk");
        if (value instanceof RiskLevel riskLevel) {
            return riskLevel;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return RiskLevel.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void putIfPresent(Map<String, Object> details, Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value != null) {
            details.put(key, value);
        }
    }

    private record Evaluation(String decision, List<String> reasons, Map<String, Object> details) {
    }
}
