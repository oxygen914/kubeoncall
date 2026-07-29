package com.kubeoncall.service;

import java.time.Duration;
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
import com.kubeoncall.common.concurrent.LeaseHeartbeat;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.task.Task;

@Service
public class AskService {

    private static final String RESUME_FINALIZED_KEY = "approvalResumeFinalized";
    private static final String CHECKPOINT_MESSAGE_KEY = "durableCheckpointMessage";

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
        return handle(question, requestedSessionId, null);
    }

    /**
     * Executes an ask workflow with a caller-assigned durable execution id.
     *
     * <p>The asynchronous worker creates the MySQL execution fact before doing external work and
     * passes its public id here. The planner preserves an existing id, so Redis GraphState,
     * approvals, audit records and the durable MySQL summary all refer to the same execution.
     */
    public AskExecutionResult handle(String question, String requestedSessionId, String executionId) {
        return handle(question, requestedSessionId, executionId, false);
    }

    /**
     * Executes an ask workflow and retains its final/paused GraphState until the MySQL task result
     * is committed. This makes a worker retry after a process crash a checkpoint replay instead of
     * a second planner/tool execution.
     */
    public AskExecutionResult handleDurably(String question, String requestedSessionId, String executionId) {
        return handleDurably(question, requestedSessionId, executionId, Map.of());
    }

    /**
     * Durable workers supply the already-authorized request actor here. Persisting this minimal
     * identity projection in GraphState lets later asynchronous boundaries (Sandbox/approval) keep
     * user attribution without re-reading a browser session or inventing a system account.
     */
    public AskExecutionResult handleDurably(
            String question, String requestedSessionId, String executionId, Map<String, Object> actor) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("A durable execution id is required");
        }
        return handle(question, requestedSessionId, executionId, true, actor);
    }

    private AskExecutionResult handle(
            String question, String requestedSessionId, String executionId, boolean retainCheckpoint) {
        return handle(question, requestedSessionId, executionId, retainCheckpoint, Map.of());
    }

    private AskExecutionResult handle(
            String question,
            String requestedSessionId,
            String executionId,
            boolean retainCheckpoint,
            Map<String, Object> actor) {
        Instant startedAt = Instant.now();
        GraphState state = new GraphState();
        if (executionId != null && !executionId.isBlank()) {
            state.setExecutionId(executionId);
        }
        state.setUserRequest(question);
        if (actor != null && !actor.isEmpty()) {
            state.getContext().put("workflowActor", Map.copyOf(actor));
        }
        String sessionId = contextLifecycle.prepare(state, question, requestedSessionId);

        plannerAgent.run(state);
        if (state.getStatus() != GraphStatus.SUCCESS) {
            return finishAndCheckpoint(state, startedAt, sessionId, retainCheckpoint);
        }

        runPlannedTasks(state, 0);
        return finishAndCheckpoint(state, startedAt, sessionId, retainCheckpoint);
    }

    public AskExecutionResult resumeAfterApproval(String executionId) {
        return resumeAfterApproval(executionId, true);
    }

    /**
     * Resumes an approved/rejected checkpoint but retains its terminal Redis snapshot until the
     * asynchronous task commits its MySQL result. A retry can therefore replay the terminal result
     * without executing tools again.
     */
    public AskExecutionResult resumeAfterApprovalDurably(String executionId) {
        return resumeAfterApproval(executionId, false);
    }

    public AskExecutionResult inspectCheckpoint(String executionId) {
        GraphState state = approvalService.loadState(executionId);
        String message = String.valueOf(
                state.getContext().getOrDefault(CHECKPOINT_MESSAGE_KEY, responseComposer.compose(state)));
        return new AskExecutionResult(
                state.getExecutionId(),
                state.getStatus().name(),
                message,
                contextLifecycle.sessionId(state),
                structuredDetails(state));
    }

    private AskExecutionResult resumeAfterApproval(String executionId, boolean clearTerminalState) {
        Instant startedAt = Instant.now();
        String resumeLease = approvalService.acquireResumeLease(executionId);
        Duration resumeLeaseTtl = approvalService.resumeLeaseTtl();
        try (LeaseHeartbeat heartbeat = LeaseHeartbeat.start(
                resumeLeaseTtl,
                () -> approvalService.renewResumeLease(executionId, resumeLease),
                "approval-resume-lease-heartbeat")) {
            GraphState state = approvalService.loadState(executionId);
            String sessionId = contextLifecycle.sessionId(state);
            if (isTerminal(state.getStatus())
                    && Boolean.TRUE.equals(state.getContext().get(RESUME_FINALIZED_KEY))) {
                AskExecutionResult replay = checkpointResult(state, sessionId);
                heartbeat.requireValid("Approval resume lease was lost while replaying terminal state");
                if (clearTerminalState) {
                    approvalService.clearState(executionId);
                }
                return replay;
            }
            if (state.getStatus() == GraphStatus.REJECTED) {
                state.addApprovalAudit("Execution terminated after rejection");
                contextLifecycle.compressRuntime(state);
                String message = responseComposer.compose(state);
                AskExecutionResult result = new AskExecutionResult(
                        state.getExecutionId(), state.getStatus().name(), message, sessionId, structuredDetails(state));
                heartbeat.requireValid("Approval resume lease was lost while finalizing rejection");
                contextLifecycle.complete(sessionId, state, message);
                executionAuditService.recordGraphExecution(ExecutionRequestType.APPROVAL_RESUME, state, startedAt);
                markResumeFinalized(state, message);
                persistOrClear(executionId, state, clearTerminalState);
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
                executorAgent.closePrepared(state);
            }
            if (state.getStatus() == GraphStatus.SUCCESS) {
                runPlannedTasks(state, state.getCurrentTaskIndex() + 1);
            }
            heartbeat.requireValid("Approval resume lease was lost while executing");
            contextLifecycle.compressRuntime(state);
            String message = responseComposer.compose(state);
            AskExecutionResult result = new AskExecutionResult(
                    state.getExecutionId(), state.getStatus().name(), message, sessionId, structuredDetails(state));
            contextLifecycle.complete(sessionId, state, message);
            executionAuditService.recordGraphExecution(ExecutionRequestType.APPROVAL_RESUME, state, startedAt);
            if (isTerminal(state.getStatus())) {
                markResumeFinalized(state, message);
                persistOrClear(executionId, state, clearTerminalState);
            } else if (!clearTerminalState) {
                approvalService.saveState(state);
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

    private AskExecutionResult checkpointResult(GraphState state, String sessionId) {
        String message = String.valueOf(
                state.getContext().getOrDefault(CHECKPOINT_MESSAGE_KEY, responseComposer.compose(state)));
        return new AskExecutionResult(
                state.getExecutionId(), state.getStatus().name(), message, sessionId, structuredDetails(state));
    }

    private void persistOrClear(String executionId, GraphState state, boolean clearTerminalState) {
        if (clearTerminalState) {
            approvalService.clearState(executionId);
        } else {
            approvalService.saveState(state);
        }
    }

    private static void markResumeFinalized(GraphState state, String message) {
        state.getContext().put(RESUME_FINALIZED_KEY, true);
        state.getContext().put(CHECKPOINT_MESSAGE_KEY, message == null ? "" : message);
    }

    private static boolean isTerminal(GraphStatus status) {
        return status == GraphStatus.SUCCESS
                || status == GraphStatus.FAILED
                || status == GraphStatus.REPLAN_REQUIRED
                || status == GraphStatus.REJECTED;
    }

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
            executorAgent.closePrepared(state);
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

    private AskExecutionResult finishAndCheckpoint(
            GraphState state, Instant startedAt, String sessionId, boolean retainCheckpoint) {
        AskExecutionResult result = finishAsk(state, startedAt, sessionId);
        if (retainCheckpoint) {
            state.getContext().put(CHECKPOINT_MESSAGE_KEY, result.message() == null ? "" : result.message());
            approvalService.saveState(state);
        }
        return result;
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
                        List.of(
                                "executionPlan",
                                "executorPayload",
                                "executorToolDefinition",
                                "toolExecutionResult",
                                "operationClosure")));
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
