package com.kubeoncall.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.agent.composer.ResponseComposer;
import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.planner.PlannerAgent;
import com.kubeoncall.agent.verifier.VerifierAgent;
import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.task.Task;

@Service
public class AskService {

    private final PlannerAgent plannerAgent;
    private final VerifierAgent verifierAgent;
    private final ExecutorAgent executorAgent;
    private final ApprovalService approvalService;
    private final ResponseComposer responseComposer;
    private final ExecutionAuditService executionAuditService;
    private final AskContextLifecycle contextLifecycle;

    public AskService(
            PlannerAgent plannerAgent,
            VerifierAgent verifierAgent,
            ExecutorAgent executorAgent,
            ApprovalService approvalService,
            ResponseComposer responseComposer,
            ExecutionAuditService executionAuditService,
            AskContextLifecycle contextLifecycle) {
        this.plannerAgent = plannerAgent;
        this.verifierAgent = verifierAgent;
        this.executorAgent = executorAgent;
        this.approvalService = approvalService;
        this.responseComposer = responseComposer;
        this.executionAuditService = executionAuditService;
        this.contextLifecycle = contextLifecycle;
    }

    public AskExecutionResult handle(String question) {
        return handle(question, null);
    }

    public AskExecutionResult handle(String question, String requestedSessionId) {
        Instant startedAt = Instant.now();
        GraphState state = new GraphState();
        state.setUserRequest(question);
        String sessionId = contextLifecycle.prepare(state, question, requestedSessionId);

        plannerAgent.run(state);
        if (state.getStatus() != GraphStatus.SUCCESS) {
            return finishAsk(state, startedAt, sessionId);
        }

        runPlannedTasks(state, 0);
        return finishAsk(state, startedAt, sessionId);
    }

    public AskExecutionResult resumeAfterApproval(String executionId) {
        Instant startedAt = Instant.now();
        String resumeLease = approvalService.acquireResumeLease(executionId);
        try {
            GraphState state = approvalService.loadState(executionId);
            String sessionId = contextLifecycle.sessionId(state);
            if (state.getStatus() == GraphStatus.REJECTED) {
                state.addApprovalAudit("Execution terminated after rejection");
                contextLifecycle.compressRuntime(state);
                String message = responseComposer.compose(state);
                AskExecutionResult result = new AskExecutionResult(
                        state.getExecutionId(), state.getStatus().name(), message, sessionId, structuredDetails(state));
                contextLifecycle.complete(sessionId, state, message);
                executionAuditService.recordGraphExecution(ExecutionRequestType.APPROVAL_RESUME, state, startedAt);
                approvalService.clearState(executionId);
                return result;
            }
            if (state.getStatus() != GraphStatus.RUNNING) {
                throw new IllegalStateException("Execution is not ready to resume: " + executionId);
            }
            if (state.getFinalApprovalDecision() != ApprovalDecision.APPROVED) {
                throw new IllegalStateException("Execution has not been approved: " + executionId);
            }

            state.addObservation("Resuming execution after approval");
            state.addApprovalAudit("Execution resumed after approval");
            state.setPauseMetadata(null);
            state.setCurrentLoop(0);
            executorAgent.executePrepared(state);
            if (state.getStatus() == GraphStatus.SUCCESS) {
                runPlannedTasks(state, state.getCurrentTaskIndex() + 1);
            }
            contextLifecycle.compressRuntime(state);
            String message = responseComposer.compose(state);
            AskExecutionResult result = new AskExecutionResult(
                    state.getExecutionId(), state.getStatus().name(), message, sessionId, structuredDetails(state));
            contextLifecycle.complete(sessionId, state, message);
            executionAuditService.recordGraphExecution(ExecutionRequestType.APPROVAL_RESUME, state, startedAt);
            if (state.getStatus() != GraphStatus.PAUSED) {
                approvalService.clearState(executionId);
            }
            return result;
        } finally {
            approvalService.releaseResumeLease(executionId, resumeLease);
        }
    }

    public ApprovalExecutionResult decideAndResume(
            String executionId, ApprovalDecision decision, String comment, String decidedBy) {
        approvalService.decide(executionId, decision, comment, decidedBy);
        AskExecutionResult result = resumeAfterApproval(executionId);
        return new ApprovalExecutionResult(
                result.executionId(), result.status(), result.message(), result.sessionId(), result.details());
    }

    public ApprovalDetailResult getApprovalDetail(String executionId) {
        ApprovalRequest request = approvalService.getApproval(executionId);
        GraphState state = approvalService.loadState(executionId);
        return new ApprovalDetailResult(request, state);
    }

    public record AskExecutionResult(
            String executionId, String status, String message, String sessionId, Map<String, Object> details) {
        public AskExecutionResult(String executionId, String status, String message) {
            this(executionId, status, message, null, Map.of());
        }
    }

    public record ApprovalExecutionResult(
            String executionId, String status, String message, String sessionId, Map<String, Object> details) {
        public ApprovalExecutionResult(String executionId, String status, String message) {
            this(executionId, status, message, null, Map.of());
        }
    }

    public record ApprovalDetailResult(ApprovalRequest approvalRequest, GraphState graphState) {}

    private void runPlannedTasks(GraphState state, int startIndex) {
        if (state.getTaskPlan() == null
                || state.getTaskPlan().tasks() == null
                || state.getTaskPlan().tasks().isEmpty()) {
            state.setStatus(GraphStatus.FAILED);
            state.addObservation("No planned tasks available for execution");
            return;
        }

        List<Task> tasks = state.getTaskPlan().tasks();
        for (int index = Math.max(0, startIndex); index < tasks.size(); index++) {
            Task task = tasks.get(index);
            state.setCurrentTaskIndex(index);
            state.setCurrentTask(task);
            state.setCurrentLoop(0);
            state.addObservation("Dispatching task " + (index + 1) + "/" + tasks.size() + ": " + task.taskId());

            executorAgent.plan(state);
            if (state.getStatus() != GraphStatus.SUCCESS) {
                return;
            }

            verifierAgent.run(state);
            if (state.getStatus() != GraphStatus.SUCCESS) {
                return;
            }

            state.setCurrentLoop(0);
            executorAgent.executePrepared(state);
            if (state.getStatus() != GraphStatus.SUCCESS) {
                return;
            }
            recordCompletedTask(state, task.taskId());
            contextLifecycle.compressRuntime(state);
        }
        state.setStatus(GraphStatus.SUCCESS);
    }

    private void recordCompletedTask(GraphState state, String taskId) {
        Object value = state.getContext().get("completedTaskIds");
        List<String> completed;
        if (value instanceof List<?> list) {
            completed = new ArrayList<>(list.stream().map(String::valueOf).toList());
            state.getContext().put("completedTaskIds", completed);
        } else {
            completed = new ArrayList<>();
            state.getContext().put("completedTaskIds", completed);
        }
        completed.add(taskId);
    }

    private AskExecutionResult finishAsk(GraphState state, Instant startedAt, String sessionId) {
        contextLifecycle.compressRuntime(state);
        String message = responseComposer.compose(state);
        contextLifecycle.complete(sessionId, state, message);
        executionAuditService.recordGraphExecution(ExecutionRequestType.ASK, state, startedAt);
        return new AskExecutionResult(
                state.getExecutionId(), state.getStatus().name(), message, sessionId, structuredDetails(state));
    }

    private Map<String, Object> structuredDetails(GraphState state) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("plan", state.getTaskPlan());
        details.put("currentTask", state.getCurrentTask());
        details.put(
                "planner",
                selectedContext(
                        state,
                        List.of(
                                "plannerSource",
                                "plannerIntent",
                                "plannerConfidence",
                                "plannerKnowledge",
                                "activatedSkillIds")));
        details.put(
                "verifier", selectedContext(state, List.of("verifierDecision", "verifierRiskReasons", "verifierTool")));
        details.put(
                "executor",
                selectedContext(
                        state,
                        List.of("executionPlan", "executorPayload", "executorToolDefinition", "toolExecutionResult")));
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("pause", state.getPauseMetadata());
        approval.put(
                "decision",
                state.getFinalApprovalDecision() == null
                        ? null
                        : state.getFinalApprovalDecision().name());
        approval.put("auditTrail", List.copyOf(state.getApprovalAuditTrail()));
        details.put("approval", approval);
        details.put("nodeResults", List.copyOf(state.getNodeResults()));
        details.put(
                "audit",
                Map.of(
                        "executionId", state.getExecutionId() == null ? "" : state.getExecutionId(),
                        "status", state.getStatus().name(),
                        "updatedAt", state.getUpdatedAt().toString()));
        return details;
    }

    private Map<String, Object> selectedContext(GraphState state, List<String> keys) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String key : keys) {
            if (state.getContext().containsKey(key)) {
                selected.put(key, state.getContext().get(key));
            }
        }
        return selected;
    }
}
