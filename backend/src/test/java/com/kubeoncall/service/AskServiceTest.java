package com.kubeoncall.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.agent.composer.ResponseComposer;
import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.planner.PlannerAgent;
import com.kubeoncall.agent.verifier.VerifierAgent;
import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.SopReference;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.memory.ContextCompressor;
import com.kubeoncall.memory.ConversationHistoryCompactor;
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryExtractor;
import com.kubeoncall.memory.MemoryInjection;
import com.kubeoncall.memory.MemoryInjector;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.memory.SessionSnapshot;
import com.kubeoncall.memory.SessionStore;
import com.kubeoncall.memory.SessionTurn;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.skill.SkillActivation;
import com.kubeoncall.skill.SkillActivationService;

class AskServiceTest {

    @Test
    void shouldRunExecutorWhenStateRemainsRunning() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        when(responseComposer.compose(any())).thenReturn("running");
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    Task task = restartTask();
                    state.setExecutionId("exec-handle");
                    state.setTaskPlan(
                            new TaskPlan("exec-handle", "restart payment-service", List.of(task), Instant.now(), true));
                    state.setCurrentTask(task);
                    state.getContext().put("plannerKnowledge", Map.of("apiToken", "must-not-be-returned"));
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(plannerAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .plan(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(verifierAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(any(GraphState.class));

        AskService.AskExecutionResult result = service.handle("请重启 payment-service");

        assertEquals("SUCCESS", result.status());
        assertEquals("exec-handle", result.executionId());
        assertEquals("exec-handle", ((TaskPlan) result.details().get("plan")).executionId());
        assertEquals("task-1", ((Task) result.details().get("currentTask")).taskId());
        assertEquals("SUCCESS", ((Map<?, ?>) result.details().get("audit")).get("status"));
        assertTrue(result.details().containsKey("planner"));
        assertTrue(result.details().containsKey("verifier"));
        assertTrue(result.details().containsKey("executor"));
        assertTrue(result.details().containsKey("approval"));
        assertTrue(result.details().containsKey("nodeResults"));
        assertFalse(((Map<?, ?>) result.details().get("planner")).containsKey("plannerKnowledge"));
        verify(executorAgent).plan(any());
        verify(verifierAgent).run(any());
        verify(executorAgent).executePrepared(any());
        verify(executionAuditService).recordGraphExecution(any(), any(), any());
    }

    @Test
    void shouldPreserveCallerAssignedDurableExecutionId() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        when(responseComposer.compose(any())).thenReturn("done");
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    assertEquals("exe_durable", state.getExecutionId());
                    Task task = restartTask();
                    state.setTaskPlan(
                            new TaskPlan("exe_durable", "restart payment-service", List.of(task), Instant.now(), true));
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(plannerAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .plan(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(verifierAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(any(GraphState.class));

        AskService.AskExecutionResult result = service.handle("请重启 payment-service", "session-1", "exe_durable");

        assertEquals("exe_durable", result.executionId());
        assertEquals("SUCCESS", result.status());
    }

    @Test
    void shouldRetainDurableAskCheckpointForWorkerCommit() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);
        when(responseComposer.compose(any())).thenReturn("planning failed");
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.FAILED);
                    return null;
                })
                .when(plannerAgent)
                .run(any(GraphState.class));

        AskService.AskExecutionResult result =
                service.handleDurably("inspect payment-service", "session-1", "exe_durable_failed");

        assertEquals("FAILED", result.status());
        ArgumentCaptor<GraphState> checkpoint = ArgumentCaptor.forClass(GraphState.class);
        verify(approvalService).saveState(checkpoint.capture());
        assertEquals("exe_durable_failed", checkpoint.getValue().getExecutionId());
        assertEquals("planning failed", checkpoint.getValue().getContext().get("durableCheckpointMessage"));
    }

    @Test
    void shouldIgnoreNullActorAndRequestScopeFieldsForDurableAsk() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        when(responseComposer.compose(any())).thenReturn("planning failed");
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    assertEquals(
                            Map.of("userId", 7L, "publicId", "usr_admin"),
                            state.getContext().get("workflowActor"));
                    assertEquals(
                            Map.of("cluster", "local", "namespace", "kubeoncall-system"),
                            state.getContext().get("requestScope"));
                    state.setStatus(GraphStatus.FAILED);
                    return null;
                })
                .when(plannerAgent)
                .run(any(GraphState.class));

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("userId", 7L);
        actor.put("publicId", "usr_admin");
        actor.put("displayName", null);
        Map<String, Object> requestScope = new LinkedHashMap<>();
        requestScope.put("cluster", "local");
        requestScope.put("environment", null);
        requestScope.put("namespace", "kubeoncall-system");

        AskService.AskExecutionResult result =
                service.handleDurably("inspect adapter", null, "exe_null_actor", actor, requestScope);

        assertEquals("FAILED", result.status());
        assertFalse(result.details().containsKey("plan"));
        assertFalse(result.details().containsKey("currentTask"));
        verify(approvalService).saveState(any(GraphState.class));
    }

    @Test
    void shouldLoadAndAppendSessionHistoryWhenSessionIdProvided() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        SessionStore sessionStore = mock(SessionStore.class);

        AskService service = askService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                sessionStore);

        when(sessionStore.find("session-1"))
                .thenReturn(Optional.of(new SessionSnapshot(
                        "session-1",
                        List.of(new SessionTurn(
                                "exec-prev", "payment-service CPU 高", "checked metrics", "SUCCESS", Instant.now())),
                        Instant.now(),
                        Instant.now())));
        when(responseComposer.compose(any())).thenReturn("ok");
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    assertEquals("session-1", state.getContext().get("sessionId"));
                    assertTrue(String.valueOf(state.getContext().get("sessionContext"))
                            .contains("payment-service"));
                    Task task = restartTask();
                    state.setExecutionId("exec-session");
                    state.setTaskPlan(new TaskPlan("exec-session", "继续看下 CPU", List.of(task), Instant.now(), true));
                    state.setCurrentTask(task);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(plannerAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .plan(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(verifierAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(any(GraphState.class));

        AskService.AskExecutionResult result = service.handle("继续看下 CPU", " session-1 ");

        assertEquals("session-1", result.sessionId());
        ArgumentCaptor<SessionTurn> turnCaptor = ArgumentCaptor.forClass(SessionTurn.class);
        verify(sessionStore).append(eq("session-1"), turnCaptor.capture());
        assertEquals("继续看下 CPU", turnCaptor.getValue().question());
        assertEquals("SUCCESS", turnCaptor.getValue().status());
    }

    @Test
    void shouldInjectMemoryContextAndIgnoreExtractorFailure() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        SessionStore sessionStore = mock(SessionStore.class);
        MemoryInjector memoryInjector = mock(MemoryInjector.class);
        MemoryExtractor memoryExtractor = mock(MemoryExtractor.class);

        AskService service = askService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                sessionStore,
                memoryInjector,
                memoryExtractor);

        when(memoryInjector.inject(eq("payment owner 是谁"), any()))
                .thenReturn(new MemoryInjection(
                        List.of(new MemoryEntry(
                                "memory-1",
                                MemoryType.SERVICE_FACT,
                                MemoryScope.SERVICE,
                                "payment owner",
                                "payment-service owned by team-payments",
                                "payment-service",
                                null,
                                null,
                                Instant.now(),
                                Instant.now(),
                                Map.of())),
                        "Long-term memory hints. Verify current cluster state before using them.",
                        ""));
        when(responseComposer.compose(any())).thenReturn("ok");
        doThrow(new IllegalStateException("extract failed"))
                .when(memoryExtractor)
                .extractFromAsk(any(GraphState.class), anyString());
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    assertEquals(
                            "Long-term memory hints. Verify current cluster state before using them.",
                            state.getContext().get("memoryContext"));
                    Task task = restartTask();
                    state.setExecutionId("exec-memory");
                    state.setTaskPlan(
                            new TaskPlan("exec-memory", "payment owner 是谁", List.of(task), Instant.now(), false));
                    state.setCurrentTask(task);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(plannerAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .plan(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(verifierAgent)
                .run(any(GraphState.class));
        doAnswer(invocation -> {
                    GraphState state = invocation.getArgument(0);
                    state.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(any(GraphState.class));

        AskService.AskExecutionResult result = service.handle("payment owner 是谁");

        assertEquals("SUCCESS", result.status());
        verify(executionAuditService).recordGraphExecution(any(), any(), any());
    }

    @Test
    void shouldResumeApprovedExecutionAndClearState() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        GraphState state = approvedRunnableState("exec-1");
        when(approvalService.acquireResumeLease("exec-1")).thenReturn("lease-1");
        when(approvalService.loadState("exec-1")).thenReturn(state);
        when(responseComposer.compose(state)).thenReturn("ok");
        doAnswer(invocation -> {
                    GraphState graphState = invocation.getArgument(0);
                    graphState.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(state);

        AskService.AskExecutionResult result = service.resumeAfterApproval("exec-1");

        assertEquals("exec-1", result.executionId());
        verify(executorAgent).executePrepared(state);
        verify(approvalService).clearState("exec-1");
        verify(executionAuditService).recordGraphExecution(any(), any(), any());
        verify(approvalService).releaseResumeLease("exec-1", "lease-1");
    }

    @Test
    void shouldRetainAndReplayTerminalResumeCheckpointWithoutExecutingToolsTwice() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);
        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        GraphState state = approvedRunnableState("exec-durable-resume");
        when(approvalService.acquireResumeLease("exec-durable-resume")).thenReturn("lease-durable");
        when(approvalService.loadState("exec-durable-resume")).thenReturn(state);
        when(responseComposer.compose(state)).thenReturn("resumed");
        doAnswer(invocation -> {
                    GraphState graphState = invocation.getArgument(0);
                    graphState.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(state);

        AskService.AskExecutionResult first = service.resumeAfterApprovalDurably("exec-durable-resume");
        AskService.AskExecutionResult replay = service.resumeAfterApprovalDurably("exec-durable-resume");

        assertEquals("SUCCESS", first.status());
        assertEquals(first.message(), replay.message());
        verify(executorAgent, times(1)).executePrepared(state);
        verify(executionAuditService, times(1)).recordGraphExecution(any(), any(), any());
        verify(approvalService, times(1)).saveState(state);
        verify(approvalService, never()).clearState("exec-durable-resume");
    }

    @Test
    void shouldRejectResumeWhenNotApproved() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        GraphState state = approvedRunnableState("exec-2");
        state.setFinalApprovalDecision(ApprovalDecision.PENDING);
        when(approvalService.acquireResumeLease("exec-2")).thenReturn("lease-2");
        when(approvalService.loadState("exec-2")).thenReturn(state);

        assertThrows(IllegalStateException.class, () -> service.resumeAfterApproval("exec-2"));
        verify(executorAgent, never()).executePrepared(any());
        verify(approvalService).releaseResumeLease("exec-2", "lease-2");
    }

    @Test
    void shouldDecideAndResume() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        GraphState state = approvedRunnableState("exec-3");
        ApprovalRequest approvalRequest = new ApprovalRequest(
                "exec-3",
                state.getTaskPlan(),
                "task-1",
                "tester",
                ApprovalDecision.APPROVED,
                Instant.now(),
                Instant.now(),
                "ok",
                "tester",
                true,
                List.of("high-risk"));
        when(approvalService.decide("exec-3", ApprovalDecision.APPROVED, "ok", "tester"))
                .thenReturn(approvalRequest);
        when(approvalService.acquireResumeLease("exec-3")).thenReturn("lease-3");
        when(approvalService.loadState("exec-3")).thenReturn(state);
        when(responseComposer.compose(state)).thenReturn("resumed");
        doAnswer(invocation -> {
                    GraphState graphState = invocation.getArgument(0);
                    graphState.setStatus(GraphStatus.SUCCESS);
                    return null;
                })
                .when(executorAgent)
                .executePrepared(state);

        AskService.ApprovalExecutionResult result =
                service.decideAndResume("exec-3", ApprovalDecision.APPROVED, "ok", "tester");

        assertEquals("exec-3", result.executionId());
        verify(approvalService).decide("exec-3", ApprovalDecision.APPROVED, "ok", "tester");
        verify(executorAgent).executePrepared(state);
    }

    @Test
    void shouldReturnApprovalDetail() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = askService(
                plannerAgent, verifierAgent, executorAgent, approvalService, responseComposer, executionAuditService);

        GraphState state = approvedRunnableState("exec-4");
        ApprovalRequest approvalRequest = new ApprovalRequest(
                "exec-4",
                state.getTaskPlan(),
                "task-1",
                "tester",
                ApprovalDecision.PENDING,
                Instant.now(),
                null,
                null,
                null,
                false,
                List.of("high-risk"));
        when(approvalService.getApproval("exec-4")).thenReturn(approvalRequest);
        when(approvalService.loadState("exec-4")).thenReturn(state);

        AskService.ApprovalDetailResult detail = service.getApprovalDetail("exec-4");

        assertEquals("exec-4", detail.approvalRequest().executionId());
        assertEquals("exec-4", detail.graphState().getExecutionId());
    }

    private GraphState approvedRunnableState(String executionId) {
        GraphState state = new GraphState();
        state.setExecutionId(executionId);
        state.setUserRequest("restart payment-service");
        state.setStatus(GraphStatus.RUNNING);
        state.setFinalApprovalDecision(ApprovalDecision.APPROVED);
        state.setResumeAttempts(1);
        Task task = restartTask();
        state.setCurrentTask(task);
        state.setTaskPlan(new TaskPlan(executionId, "restart payment-service", List.of(task), Instant.now(), true));
        state.addNodeResult(new NodeResult("verifierApprovalNode", NodeStatus.SUCCESS, "approved", Map.of()));
        return state;
    }

    private Task restartTask() {
        return new Task(
                "task-1",
                "restart",
                TaskType.RESTART_SERVICE,
                RiskLevel.HIGH,
                "payment-service",
                Map.of("rolloutStrategy", "rolling"),
                new SopReference("SOP-RESTART_SERVICE", "restart", "v1", "rag:sop"));
    }

    private AskService askService(
            PlannerAgent plannerAgent,
            VerifierAgent verifierAgent,
            ExecutorAgent executorAgent,
            ApprovalService approvalService,
            ResponseComposer responseComposer,
            ExecutionAuditService auditService) {
        return askService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                auditService,
                SessionStore.noop(),
                MemoryInjector.noop(),
                MemoryExtractor.noop());
    }

    private AskService askService(
            PlannerAgent plannerAgent,
            VerifierAgent verifierAgent,
            ExecutorAgent executorAgent,
            ApprovalService approvalService,
            ResponseComposer responseComposer,
            ExecutionAuditService auditService,
            SessionStore sessionStore) {
        return askService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                auditService,
                sessionStore,
                MemoryInjector.noop(),
                MemoryExtractor.noop());
    }

    private AskService askService(
            PlannerAgent plannerAgent,
            VerifierAgent verifierAgent,
            ExecutorAgent executorAgent,
            ApprovalService approvalService,
            ResponseComposer responseComposer,
            ExecutionAuditService auditService,
            SessionStore sessionStore,
            MemoryInjector memoryInjector,
            MemoryExtractor memoryExtractor) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        TokenBudget tokenBudget = new TokenBudget();
        SkillActivationService skillActivationService = mock(SkillActivationService.class);
        when(skillActivationService.activate(anyString(), any())).thenReturn(SkillActivation.empty());
        AskContextLifecycle contextLifecycle = new AskContextLifecycle(
                sessionStore,
                memoryInjector,
                memoryExtractor,
                skillActivationService,
                new ContextCompressor(properties, tokenBudget),
                new ConversationHistoryCompactor(properties, tokenBudget));
        return new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                auditService,
                contextLifecycle);
    }
}
