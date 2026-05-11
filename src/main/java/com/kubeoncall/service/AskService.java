package com.kubeoncall.service;

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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AskService {

    private final PlannerAgent plannerAgent;
    private final VerifierAgent verifierAgent;
    private final ExecutorAgent executorAgent;
    private final ApprovalService approvalService;
    private final ResponseComposer responseComposer;
    private final ExecutionAuditService executionAuditService;

    public AskService(PlannerAgent plannerAgent,
                      VerifierAgent verifierAgent,
                      ExecutorAgent executorAgent,
                      ApprovalService approvalService,
                      ResponseComposer responseComposer,
                      ExecutionAuditService executionAuditService) {
        this.plannerAgent = plannerAgent;
        this.verifierAgent = verifierAgent;
        this.executorAgent = executorAgent;
        this.approvalService = approvalService;
        this.responseComposer = responseComposer;
        this.executionAuditService = executionAuditService;
    }

    public AskExecutionResult handle(String question) {
        Instant startedAt = Instant.now();
        GraphState state = new GraphState();
        state.setUserRequest(question);

        plannerAgent.run(state);
        if (state.getStatus() != GraphStatus.SUCCESS) {
            executionAuditService.recordGraphExecution(ExecutionRequestType.ASK, state, startedAt);
            return new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), responseComposer.compose(state));
        }

        runPlannedTasks(state, 0);
        executionAuditService.recordGraphExecution(ExecutionRequestType.ASK, state, startedAt);
        return new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), responseComposer.compose(state));
    }

    public AskExecutionResult resumeAfterApproval(String executionId) {
        Instant startedAt = Instant.now();
        GraphState state = approvalService.loadState(executionId);
        if (state.getStatus() == GraphStatus.REJECTED) {
            state.addApprovalAudit("Execution terminated after rejection");
            AskExecutionResult result = new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), responseComposer.compose(state));
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
        AskExecutionResult result = new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), responseComposer.compose(state));
        executionAuditService.recordGraphExecution(ExecutionRequestType.APPROVAL_RESUME, state, startedAt);
        if (state.getStatus() != GraphStatus.PAUSED) {
            approvalService.clearState(executionId);
        }
        return result;
    }

    public ApprovalExecutionResult decideAndResume(String executionId, ApprovalDecision decision, String comment, String decidedBy) {
        approvalService.decide(executionId, decision, comment, decidedBy);
        AskExecutionResult result = resumeAfterApproval(executionId);
        return new ApprovalExecutionResult(result.executionId(), result.status(), result.message());
    }

    public ApprovalDetailResult getApprovalDetail(String executionId) {
        ApprovalRequest request = approvalService.getApproval(executionId);
        GraphState state = approvalService.loadState(executionId);
        return new ApprovalDetailResult(request, state);
    }

    public record AskExecutionResult(String executionId, String status, String message) {
    }

    public record ApprovalExecutionResult(String executionId, String status, String message) {
    }

    public record ApprovalDetailResult(ApprovalRequest approvalRequest, GraphState graphState) {
    }

    private void runPlannedTasks(GraphState state, int startIndex) {
        if (state.getTaskPlan() == null || state.getTaskPlan().tasks() == null || state.getTaskPlan().tasks().isEmpty()) {
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
        }
        state.setStatus(GraphStatus.SUCCESS);
    }

    @SuppressWarnings("unchecked")
    private void recordCompletedTask(GraphState state, String taskId) {
        Object value = state.getContext().get("completedTaskIds");
        List<String> completed;
        if (value instanceof List<?> list) {
            completed = (List<String>) list;
        } else {
            completed = new ArrayList<>();
            state.getContext().put("completedTaskIds", completed);
        }
        completed.add(taskId);
    }
}
