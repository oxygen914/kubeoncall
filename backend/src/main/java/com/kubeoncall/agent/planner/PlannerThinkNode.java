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
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConclusionFactory;
import com.kubeoncall.evidence.EvidenceClaimGrounder;
import com.kubeoncall.skill.SkillActivation;

@Component
public class PlannerThinkNode extends ThinkNode {

    private final PlannerLlmService plannerLlmService;
    private final PlannerContextAssembler contextAssembler;
    private final PlannerRuleEngine ruleEngine;
    private final ConclusionFactory conclusionFactory;
    private final EvidenceClaimGrounder evidenceClaimGrounder;

    public PlannerThinkNode(
            PlannerLlmService plannerLlmService,
            PlannerContextAssembler contextAssembler,
            PlannerRuleEngine ruleEngine,
            ConclusionFactory conclusionFactory,
            EvidenceClaimGrounder evidenceClaimGrounder) {
        this.plannerLlmService = plannerLlmService;
        this.contextAssembler = contextAssembler;
        this.ruleEngine = ruleEngine;
        this.conclusionFactory = conclusionFactory;
        this.evidenceClaimGrounder = evidenceClaimGrounder;
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
                ruleEngine.identifyMissingSignals(normalized, taskType, target, parameters, plannerKnowledge);
        List<String> consultedTools = contextAssembler.consultedTools(state);
        Map<String, String> evidenceSources = contextAssembler.evidenceSources(plannerKnowledge);
        String plannerSource = "rules";

        PlannerLlmResult llmResult = plannerLlmService.planWithStatus(normalized, plannerKnowledge);
        PlannerLlmDecision llmDecision = llmResult.decision();
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
        if (ruleEngine.isExplicitReadOnlyRequest(normalized) && ruleEngine.isMutation(taskType)) {
            intent = ruleEngine.inferIntent(normalized);
            taskType = ruleEngine.mapIntentToTaskType(intent);
            parameters = ruleEngine.inferParameters(normalized, taskType, target, plannerKnowledge);
            parameterSources = ruleEngine.buildParameterSources(parameters, normalized, plannerKnowledge);
            riskLevel = ruleEngine.inferRiskLevel(intent, taskType, target, parameters, plannerKnowledge);
            missingSignals =
                    ruleEngine.identifyMissingSignals(normalized, taskType, target, parameters, plannerKnowledge);
            plannerSource = plannerSource + "+read_only_guard";
            state.addObservation("Planner mutation decision was replaced by the explicit read-only request guard");
        }
        String planSummary = ruleEngine.buildPlanSummary(intent, taskType, target, riskLevel, consultedTools);
        if (llmDecision != null
                && llmDecision.summary() != null
                && !llmDecision.summary().isBlank()) {
            planSummary = llmDecision.summary();
        }
        EvidenceClaimGrounder.GroundedClaim groundedClaim = evidenceClaimGrounder.ground(
                taskType,
                target,
                planSummary,
                missingSignals,
                state.getContext().get("evidenceItems"));
        planSummary = groundedClaim.claim();
        missingSignals = groundedClaim.missingSignals();

        if (ruleEngine.shouldRetryForMissingSignals(taskType, missingSignals, state.getCurrentLoop())) {
            state.getContext().put("plannerMissingSignals", missingSignals);
            return NodeResult.retry(
                    getName(),
                    "Planner requires additional signals: " + String.join(", ", missingSignals),
                    "MISSING_SIGNALS",
                    "QUERY_ADDITIONAL_CONTEXT",
                    Map.of("missingSignals", missingSignals, "taskType", taskType.name(), "target", target));
        }
        if (ruleEngine.requiresClarification(taskType, missingSignals, state.getCurrentLoop())) {
            state.getContext().put("plannerMissingSignals", missingSignals);
            return new NodeResult(
                    getName(),
                    NodeStatus.FAILURE,
                    "Planner requires a specific target or change parameter before creating an operation",
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
                evidenceSources,
                llmResult.mode().name(),
                llmResult.degraded(),
                llmResult.degradedReason() == null
                        ? null
                        : llmResult.degradedReason().name(),
                llmResult.provider(),
                llmResult.model(),
                llmResult.tokenUsage());
        state.getContext().put("plannerSummary", summary);
        state.getContext().put("plannerIntent", intent);
        state.getContext().put("plannerConfidence", confidence);
        state.getContext().put("plannerSource", plannerSource);
        state.getContext().put("plannerMode", llmResult.mode().name());
        state.getContext().put("plannerDegraded", llmResult.degraded());
        if (llmResult.degradedReason() != null) {
            state.getContext()
                    .put("plannerDegradedReason", llmResult.degradedReason().name());
        } else {
            state.getContext().remove("plannerDegradedReason");
        }
        state.getContext().put("plannerProvider", llmResult.provider());
        state.getContext().put("plannerModel", llmResult.model());
        state.getContext().put("plannerTokenUsage", llmResult.tokenUsage());
        state.getContext().put("simulation", llmResult.mode() == PlannerMode.SIMULATION);

        SopReference sopReference = ruleEngine.resolveSopReference(plannerKnowledge);
        List<Task> tasks = new ArrayList<>();
        tasks.add(ruleEngine.buildTask(intent, taskType, target, parameters, riskLevel, sopReference));
        List<String> taskRequests = ruleEngine.splitTaskRequests(normalized);
        for (int index = 1; index < taskRequests.size(); index++) {
            String taskRequest = taskRequests.get(index);
            if (ruleEngine.isExplicitReadOnlyRequest(taskRequest)) {
                state.addObservation("Planner retained explicit read-only safety constraint: " + taskRequest);
                continue;
            }
            String taskIntent = ruleEngine.inferIntent(taskRequest);
            TaskType inferredTaskType = ruleEngine.mapIntentToTaskType(taskIntent);
            if (ruleEngine.isMutation(inferredTaskType)) {
                state.getContext().put("plannerRejectedCompoundMutation", taskRequest);
                return new NodeResult(
                        getName(),
                        NodeStatus.FAILURE,
                        "Each mutating instruction must be submitted as a separate execution for model validation",
                        Map.of(
                                "rejectedRequest",
                                taskRequest,
                                "taskType",
                                inferredTaskType.name(),
                                "reason",
                                "COMPOUND_MUTATION_REQUIRES_SEPARATE_EXECUTION"));
            }
            String inferredTarget = ruleEngine.inferTarget(taskRequest);
            Map<String, Object> inferredParameters =
                    ruleEngine.inferParameters(taskRequest, inferredTaskType, inferredTarget, plannerKnowledge);
            RiskLevel inferredRiskLevel = ruleEngine.inferRiskLevel(
                    taskIntent, inferredTaskType, inferredTarget, inferredParameters, plannerKnowledge);
            tasks.add(ruleEngine.buildTask(
                    taskIntent, inferredTaskType, inferredTarget, inferredParameters, inferredRiskLevel, sopReference));
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
        AiConclusion conclusion = conclusionFactory.create(state, task, planSummary);
        state.getContext().put("conclusions", List.of(conclusion));
        state.addObservation("Planner: intent=" + intent + ", confidence=" + confidence + ", target=" + target
                + ", risk=" + riskLevel + ", mode=" + llmResult.mode() + ", consultedTools=" + consultedTools);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskCount", taskPlan.tasks().size());
        payload.put("intent", intent);
        payload.put("confidence", confidence);
        payload.put("plannerSource", plannerSource);
        payload.put("plannerMode", llmResult.mode().name());
        payload.put("degraded", llmResult.degraded());
        payload.put(
                "degradedReason",
                llmResult.degradedReason() == null
                        ? null
                        : llmResult.degradedReason().name());
        payload.put("provider", llmResult.provider());
        payload.put("model", llmResult.model());
        payload.put("evidenceConfidence", conclusion.confidence());
        payload.put("conclusionId", conclusion.conclusionId());
        payload.put("consultedTools", consultedTools);
        payload.put("evidenceSources", evidenceSources);
        return new NodeResult(getName(), NodeStatus.SUCCESS, "Planner produced task plan", payload);
    }
}
