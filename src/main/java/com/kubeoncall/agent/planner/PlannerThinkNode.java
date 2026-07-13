package com.kubeoncall.agent.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.kubeoncall.agent.node.ThinkNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.PlannerSummary;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.skill.SkillActivation;

@Component
public class PlannerThinkNode extends ThinkNode {

    private final PlannerLlmService plannerLlmService;
    private final PlannerContextAssembler contextAssembler;
    private final PlannerRuleEngine ruleEngine;

    public PlannerThinkNode(
            PlannerLlmService plannerLlmService,
            PlannerContextAssembler contextAssembler,
            PlannerRuleEngine ruleEngine) {
        this.plannerLlmService = plannerLlmService;
        this.contextAssembler = contextAssembler;
        this.ruleEngine = ruleEngine;
    }

    @Override
    public String getName() {
        return "plannerThinkNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        String executionId = state.getExecutionId() == null ? UUID.randomUUID().toString() : state.getExecutionId();
        state.setExecutionId(executionId);

        String userRequest = state.getUserRequest();
        String normalized = contextAssembler.normalizeRequest(contextAssembler.planningRequest(state, userRequest));
        String intent = ruleEngine.inferIntent(normalized);
        String confidence = ruleEngine.inferConfidence(normalized, intent);
        String target = ruleEngine.inferTarget(normalized);
        String targetSource = ruleEngine.inferTargetSource(normalized, target);
        TaskType taskType = ruleEngine.mapIntentToTaskType(intent);
        Map<String, Object> plannerKnowledge = contextAssembler.plannerKnowledge(state);
        Map<String, Object> parameters = ruleEngine.inferParameters(normalized, taskType, target, plannerKnowledge);
        Map<String, String> parameterSources =
                ruleEngine.buildParameterSources(parameters, normalized, plannerKnowledge);
        RiskLevel riskLevel = ruleEngine.inferRiskLevel(intent, taskType, target, parameters, plannerKnowledge);
        List<String> missingSignals =
                ruleEngine.identifyMissingSignals(normalized, taskType, parameters, plannerKnowledge);
        List<String> consultedTools = contextAssembler.consultedTools(state);
        Map<String, String> evidenceSources = contextAssembler.evidenceSources(plannerKnowledge);
        String plannerSource = "rules";

        PlannerLlmDecision llmDecision =
                plannerLlmService.plan(normalized, plannerKnowledge).orElse(null);
        if (llmDecision != null) {
            SkillActivation requestedActivation = plannerLlmService.activateRequestedSkills(
                    normalized, state.getContext(), llmDecision.requestedSkills());
            if (requestedActivation.active()) {
                contextAssembler.applySkillActivation(state, requestedActivation);
                plannerKnowledge = contextAssembler.plannerKnowledge(state);
            }
            intent = ruleEngine.defaultString(llmDecision.intent(), intent);
            confidence = ruleEngine.defaultString(llmDecision.confidence(), confidence);
            target = ruleEngine.defaultString(llmDecision.target(), target);
            targetSource = ruleEngine.defaultString(llmDecision.targetSource(), "llm");
            taskType = llmDecision.taskType() == null ? ruleEngine.mapIntentToTaskType(intent) : llmDecision.taskType();
            parameters = ruleEngine.mergeParameters(
                    ruleEngine.inferParameters(normalized, taskType, target, plannerKnowledge),
                    llmDecision.parameters());
            parameterSources = ruleEngine.markLlmParameterSources(
                    ruleEngine.buildParameterSources(parameters, normalized, plannerKnowledge),
                    llmDecision.parameters());
            riskLevel = llmDecision.riskLevel() == null
                    ? ruleEngine.inferRiskLevel(intent, taskType, target, parameters, plannerKnowledge)
                    : llmDecision.riskLevel();
            missingSignals = ruleEngine.mergeMissingSignals(missingSignals, llmDecision.missingSignals());
            plannerSource = "llm";
        }
        String planSummary = ruleEngine.buildPlanSummary(intent, taskType, target, riskLevel, consultedTools);
        if (llmDecision != null
                && llmDecision.summary() != null
                && !llmDecision.summary().isBlank()) {
            planSummary = llmDecision.summary();
        }

        if (ruleEngine.shouldRetryForMissingSignals(taskType, missingSignals, state.getCurrentLoop())) {
            state.getContext().put("plannerMissingSignals", missingSignals);
            return NodeResult.retry(
                    getName(),
                    "Planner requires additional signals: " + String.join(", ", missingSignals),
                    "MISSING_SIGNALS",
                    "QUERY_ADDITIONAL_CONTEXT",
                    Map.of("missingSignals", missingSignals, "taskType", taskType.name(), "target", target));
        }

        state.getContext().remove("plannerMissingSignals");
        PlannerSummary summary = new PlannerSummary(
                normalized,
                intent,
                confidence,
                target,
                targetSource,
                parameterSources,
                missingSignals,
                planSummary,
                consultedTools,
                evidenceSources);
        state.getContext().put("plannerSummary", summary);
        state.getContext().put("plannerIntent", intent);
        state.getContext().put("plannerConfidence", confidence);
        state.getContext().put("plannerSource", plannerSource);

        List<Task> tasks = new ArrayList<>();
        tasks.add(ruleEngine.buildTask(intent, taskType, target, parameters, riskLevel));
        List<String> taskRequests = ruleEngine.splitTaskRequests(normalized);
        for (int index = 1; index < taskRequests.size(); index++) {
            String taskRequest = taskRequests.get(index);
            String taskIntent = ruleEngine.inferIntent(taskRequest);
            TaskType inferredTaskType = ruleEngine.mapIntentToTaskType(taskIntent);
            String inferredTarget = ruleEngine.inferTarget(taskRequest);
            Map<String, Object> inferredParameters =
                    ruleEngine.inferParameters(taskRequest, inferredTaskType, inferredTarget, plannerKnowledge);
            RiskLevel inferredRiskLevel = ruleEngine.inferRiskLevel(
                    taskIntent, inferredTaskType, inferredTarget, inferredParameters, plannerKnowledge);
            tasks.add(ruleEngine.buildTask(
                    taskIntent, inferredTaskType, inferredTarget, inferredParameters, inferredRiskLevel));
        }
        Task task = tasks.get(0);
        TaskPlan taskPlan = new TaskPlan(
                executionId,
                userRequest,
                tasks,
                Instant.now(),
                tasks.stream().anyMatch(item -> item.riskLevel().ordinal() >= RiskLevel.HIGH.ordinal()));

        state.setTaskPlan(taskPlan);
        state.setCurrentTask(task);
        state.addObservation("Planner: intent=" + intent + ", confidence=" + confidence + ", target=" + target
                + ", risk=" + riskLevel + ", source=" + plannerSource + ", consultedTools=" + consultedTools);
        return new NodeResult(
                getName(),
                NodeStatus.SUCCESS,
                "Planner produced task plan",
                Map.of(
                        "taskCount", taskPlan.tasks().size(),
                        "intent", intent,
                        "confidence", confidence,
                        "plannerSource", plannerSource,
                        "consultedTools", consultedTools,
                        "evidenceSources", evidenceSources));
    }
}
