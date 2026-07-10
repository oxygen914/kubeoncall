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
import com.kubeoncall.memory.MemoryInjection;
import com.kubeoncall.memory.MemoryExtractor;
import com.kubeoncall.memory.MemoryInjector;
import com.kubeoncall.memory.ContextCompressor;
import com.kubeoncall.memory.ConversationHistoryCompactor;
import com.kubeoncall.memory.SessionSnapshot;
import com.kubeoncall.memory.SessionStore;
import com.kubeoncall.memory.SessionTurn;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillActivationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AskService {

    private final PlannerAgent plannerAgent;
    private final VerifierAgent verifierAgent;
    private final ExecutorAgent executorAgent;
    private final ApprovalService approvalService;
    private final ResponseComposer responseComposer;
    private final ExecutionAuditService executionAuditService;
    private final SessionStore sessionStore;
    private final MemoryInjector memoryInjector;
    private final MemoryExtractor memoryExtractor;
    private final SkillActivationService skillActivationService;
    private final ContextCompressor contextCompressor;
    private final ConversationHistoryCompactor historyCompactor;

    @Autowired
    public AskService(PlannerAgent plannerAgent,
                      VerifierAgent verifierAgent,
                      ExecutorAgent executorAgent,
                      ApprovalService approvalService,
                      ResponseComposer responseComposer,
                      ExecutionAuditService executionAuditService,
                      SessionStore sessionStore,
                      MemoryInjector memoryInjector,
                      MemoryExtractor memoryExtractor,
                      SkillActivationService skillActivationService,
                      ContextCompressor contextCompressor,
                      ConversationHistoryCompactor historyCompactor) {
        this.plannerAgent = plannerAgent;
        this.verifierAgent = verifierAgent;
        this.executorAgent = executorAgent;
        this.approvalService = approvalService;
        this.responseComposer = responseComposer;
        this.executionAuditService = executionAuditService;
        this.sessionStore = sessionStore;
        this.memoryInjector = memoryInjector;
        this.memoryExtractor = memoryExtractor;
        this.skillActivationService = skillActivationService;
        this.contextCompressor = contextCompressor;
        this.historyCompactor = historyCompactor;
    }

    public AskService(PlannerAgent plannerAgent,
                      VerifierAgent verifierAgent,
                      ExecutorAgent executorAgent,
                      ApprovalService approvalService,
                      ResponseComposer responseComposer,
                      ExecutionAuditService executionAuditService,
                      SessionStore sessionStore,
                      MemoryInjector memoryInjector,
                      MemoryExtractor memoryExtractor,
                      SkillActivationService skillActivationService) {
        this(plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer,
                executionAuditService, sessionStore, memoryInjector, memoryExtractor,
                skillActivationService, null, null);
    }

    public AskService(PlannerAgent plannerAgent,
                      VerifierAgent verifierAgent,
                      ExecutorAgent executorAgent,
                      ApprovalService approvalService,
                      ResponseComposer responseComposer,
                      ExecutionAuditService executionAuditService,
                      SessionStore sessionStore,
                      MemoryInjector memoryInjector,
                      MemoryExtractor memoryExtractor) {
        this(plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                sessionStore,
                memoryInjector,
                memoryExtractor,
                null);
    }

    public AskService(PlannerAgent plannerAgent,
                      VerifierAgent verifierAgent,
                      ExecutorAgent executorAgent,
                      ApprovalService approvalService,
                      ResponseComposer responseComposer,
                      ExecutionAuditService executionAuditService,
                      SessionStore sessionStore) {
        this(plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                sessionStore,
                MemoryInjector.noop(),
                MemoryExtractor.noop(),
                null);
    }

    public AskService(PlannerAgent plannerAgent,
                      VerifierAgent verifierAgent,
                      ExecutorAgent executorAgent,
                      ApprovalService approvalService,
                      ResponseComposer responseComposer,
                      ExecutionAuditService executionAuditService) {
        this(plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                SessionStore.noop(),
                MemoryInjector.noop(),
                MemoryExtractor.noop(),
                null);
    }

    public AskExecutionResult handle(String question) {
        return handle(question, null);
    }

    public AskExecutionResult handle(String question, String requestedSessionId) {
        Instant startedAt = Instant.now();
        GraphState state = new GraphState();
        state.setUserRequest(question);
        String sessionId = normalizeSessionId(requestedSessionId);
        attachSessionContext(state, sessionId);
        attachMemoryContext(state, question);
        attachSkillContext(state, question);
        compressPlanningContext(state);

        plannerAgent.run(state);
        if (state.getStatus() != GraphStatus.SUCCESS) {
            return finishAsk(state, startedAt, sessionId);
        }

        runPlannedTasks(state, 0);
        return finishAsk(state, startedAt, sessionId);
    }

    public AskExecutionResult resumeAfterApproval(String executionId) {
        Instant startedAt = Instant.now();
        GraphState state = approvalService.loadState(executionId);
        String sessionId = contextSessionId(state);
        if (state.getStatus() == GraphStatus.REJECTED) {
            state.addApprovalAudit("Execution terminated after rejection");
            compressRuntime(state);
            String message = responseComposer.compose(state);
            AskExecutionResult result = new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), message, sessionId);
            appendSessionTurn(sessionId, state, message);
            extractMemory(state, message);
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
        compressRuntime(state);
        String message = responseComposer.compose(state);
        AskExecutionResult result = new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), message, sessionId);
        appendSessionTurn(sessionId, state, message);
        extractMemory(state, message);
        executionAuditService.recordGraphExecution(ExecutionRequestType.APPROVAL_RESUME, state, startedAt);
        if (state.getStatus() != GraphStatus.PAUSED) {
            approvalService.clearState(executionId);
        }
        return result;
    }

    public ApprovalExecutionResult decideAndResume(String executionId, ApprovalDecision decision, String comment, String decidedBy) {
        approvalService.decide(executionId, decision, comment, decidedBy);
        AskExecutionResult result = resumeAfterApproval(executionId);
        return new ApprovalExecutionResult(result.executionId(), result.status(), result.message(), result.sessionId());
    }

    public ApprovalDetailResult getApprovalDetail(String executionId) {
        ApprovalRequest request = approvalService.getApproval(executionId);
        GraphState state = approvalService.loadState(executionId);
        return new ApprovalDetailResult(request, state);
    }

    public record AskExecutionResult(String executionId, String status, String message, String sessionId) {
        public AskExecutionResult(String executionId, String status, String message) {
            this(executionId, status, message, null);
        }
    }

    public record ApprovalExecutionResult(String executionId, String status, String message, String sessionId) {
        public ApprovalExecutionResult(String executionId, String status, String message) {
            this(executionId, status, message, null);
        }
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
            compressRuntime(state);
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

    private AskExecutionResult finishAsk(GraphState state, Instant startedAt, String sessionId) {
        compressRuntime(state);
        String message = responseComposer.compose(state);
        appendSessionTurn(sessionId, state, message);
        extractMemory(state, message);
        executionAuditService.recordGraphExecution(ExecutionRequestType.ASK, state, startedAt);
        return new AskExecutionResult(state.getExecutionId(), state.getStatus().name(), message, sessionId);
    }

    private void attachSessionContext(GraphState state, String sessionId) {
        if (sessionId == null) {
            return;
        }
        state.getContext().put("sessionId", sessionId);
        try {
            SessionSnapshot snapshot = sessionStore.find(sessionId).orElse(null);
            if (snapshot == null || snapshot.turns().isEmpty()) {
                state.getContext().put("sessionHistory", List.of());
                state.getContext().put("sessionContext", "");
                return;
            }
            List<Map<String, String>> history = snapshot.turns().stream()
                    .map(this::toSessionHistoryMap)
                    .toList();
            state.getContext().put("sessionHistory", history);
            state.getContext().put("sessionSummary", snapshot.summary());
            state.getContext().put("sessionCompactedTurnCount", snapshot.compactedTurnCount());
            state.getContext().put("sessionContext", historyCompactor == null
                    ? buildSessionContext(snapshot.turns())
                    : historyCompactor.buildContext(snapshot));
        } catch (RuntimeException ex) {
            state.getContext().put("sessionHistory", List.of());
            state.getContext().put("sessionContext", "");
            state.getContext().put("sessionWarning", "session history unavailable: " + ex.getMessage());
        }
    }

    private Map<String, String> toSessionHistoryMap(SessionTurn turn) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("executionId", defaultString(turn.executionId()));
        map.put("question", abbreviate(turn.question(), 300));
        map.put("answer", abbreviate(turn.answer(), 500));
        map.put("status", defaultString(turn.status()));
        map.put("createdAt", turn.createdAt() == null ? "" : turn.createdAt().toString());
        return map;
    }

    private void attachMemoryContext(GraphState state, String question) {
        try {
            MemoryInjection injection = memoryInjector.inject(question, Map.of());
            if (!injection.prompt().isBlank()) {
                state.getContext().put("memoryContext", injection.prompt());
                state.getContext().put("injectedMemoryCount", injection.entries().size());
            }
            if (!injection.warning().isBlank()) {
                state.getContext().put("memoryWarning", injection.warning());
            }
        } catch (RuntimeException ex) {
            state.getContext().put("memoryWarning", "memory injection failed: " + ex.getMessage());
        }
    }

    private void attachSkillContext(GraphState state, String question) {
        if (skillActivationService == null) {
            return;
        }
        try {
            SkillActivation activation = skillActivationService.activate(question, state.getContext());
            if (!activation.active()) {
                state.getContext().put("activatedSkills", List.of());
                return;
            }
            state.getContext().put("activatedSkills", activation.skillSummaries());
            state.getContext().put("activatedSkillIds", activation.skillIds());
            state.getContext().put("skillPrompt", activation.prompt());
            state.getContext().put("activatedSkillToolWhitelist", activation.toolWhitelist());
            if (activation.maxRisk() != null) {
                state.getContext().put("activatedSkillMaxRisk", activation.maxRisk().name());
            }
            mergePlannerSkillKnowledge(state, activation);
        } catch (RuntimeException ex) {
            state.getContext().put("skillWarning", "skill activation failed: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void mergePlannerSkillKnowledge(GraphState state, SkillActivation activation) {
        Object value = state.getContext().get("plannerKnowledge");
        Map<String, Object> plannerKnowledge;
        if (value instanceof Map<?, ?> raw) {
            plannerKnowledge = new LinkedHashMap<>();
            raw.forEach((key, entryValue) -> plannerKnowledge.put(String.valueOf(key), entryValue));
        } else {
            plannerKnowledge = new LinkedHashMap<>();
        }
        plannerKnowledge.put("activatedSkills", activation.skillSummaries());
        plannerKnowledge.put("activatedSkillIds", activation.skillIds());
        plannerKnowledge.put("activatedSkillToolWhitelist", activation.toolWhitelist());
        plannerKnowledge.put("activatedSkillMaxRisk", activation.maxRisk() == null ? null : activation.maxRisk().name());
        plannerKnowledge.put("skillPrompt", activation.prompt());
        state.getContext().put("plannerKnowledge", plannerKnowledge);
    }

    private String buildSessionContext(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        List<SessionTurn> recent = turns.size() > 4 ? turns.subList(turns.size() - 4, turns.size()) : turns;
        StringBuilder builder = new StringBuilder();
        for (SessionTurn turn : recent) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append("Previous user: ").append(abbreviate(turn.question(), 240))
                    .append("\nPrevious assistant status: ").append(defaultString(turn.status()))
                    .append("\nPrevious assistant summary: ").append(abbreviate(turn.answer(), 360));
        }
        return builder.toString();
    }

    private void appendSessionTurn(String sessionId, GraphState state, String message) {
        if (sessionId == null) {
            return;
        }
        try {
            sessionStore.append(sessionId, new SessionTurn(
                    state.getExecutionId(),
                    state.getUserRequest(),
                    message,
                    state.getStatus() == null ? null : state.getStatus().name(),
                    Instant.now()
            ));
        } catch (RuntimeException ignored) {
            // Session memory must not interrupt the main ask workflow.
        }
    }

    private void extractMemory(GraphState state, String message) {
        try {
            memoryExtractor.extractFromAsk(state, message);
        } catch (RuntimeException ex) {
            state.getContext().put("memoryExtractionWarning", "memory extraction failed: " + ex.getMessage());
        }
    }

    private void compressPlanningContext(GraphState state) {
        if (contextCompressor != null) {
            contextCompressor.compressPlanningContext(state);
        }
    }

    private void compressRuntime(GraphState state) {
        if (contextCompressor != null) {
            contextCompressor.compressRuntime(state);
        }
    }

    private String contextSessionId(GraphState state) {
        Object value = state.getContext().get("sessionId");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
    }

    private String abbreviate(String value, int maxLength) {
        String normalized = defaultString(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
