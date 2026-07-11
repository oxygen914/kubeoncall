package com.kubeoncall.service;

import com.kubeoncall.agent.composer.ResponseComposer;
import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.planner.PlannerAgent;
import com.kubeoncall.agent.verifier.VerifierAgent;
import com.kubeoncall.approval.ApprovalService;
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
import com.kubeoncall.memory.MemoryEntry;
import com.kubeoncall.memory.MemoryExtractor;
import com.kubeoncall.memory.MemoryInjection;
import com.kubeoncall.memory.MemoryInjector;
import com.kubeoncall.memory.MemoryScope;
import com.kubeoncall.memory.MemoryType;
import com.kubeoncall.memory.SessionSnapshot;
import com.kubeoncall.memory.SessionStore;
import com.kubeoncall.memory.SessionTurn;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskServiceTest {

    @Test
    void shouldRunExecutorWhenStateRemainsRunning() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService
        );

        when(responseComposer.compose(any())).thenReturn("running");
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            Task task = restartTask();
            state.setExecutionId("exec-handle");
            state.setTaskPlan(new TaskPlan("exec-handle", "restart payment-service", List.of(task), Instant.now(), true));
            state.setCurrentTask(task);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(plannerAgent).run(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).plan(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(verifierAgent).run(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).executePrepared(any(GraphState.class));

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
        verify(executorAgent).plan(any());
        verify(verifierAgent).run(any());
        verify(executorAgent).executePrepared(any());
        verify(executionAuditService).recordGraphExecution(any(), any(), any());
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

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                sessionStore
        );

        when(sessionStore.find("session-1")).thenReturn(Optional.of(new SessionSnapshot(
                "session-1",
                List.of(new SessionTurn("exec-prev", "payment-service CPU 高", "checked metrics", "SUCCESS", Instant.now())),
                Instant.now(),
                Instant.now()
        )));
        when(responseComposer.compose(any())).thenReturn("ok");
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            assertEquals("session-1", state.getContext().get("sessionId"));
            assertTrue(String.valueOf(state.getContext().get("sessionContext")).contains("payment-service"));
            Task task = restartTask();
            state.setExecutionId("exec-session");
            state.setTaskPlan(new TaskPlan("exec-session", "继续看下 CPU", List.of(task), Instant.now(), true));
            state.setCurrentTask(task);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(plannerAgent).run(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).plan(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(verifierAgent).run(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).executePrepared(any(GraphState.class));

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

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService,
                sessionStore,
                memoryInjector,
                memoryExtractor
        );

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
                                Map.of()
                        )),
                        "Long-term memory hints. Verify current cluster state before using them.",
                        ""
                ));
        when(responseComposer.compose(any())).thenReturn("ok");
        doThrow(new IllegalStateException("extract failed"))
                .when(memoryExtractor).extractFromAsk(any(GraphState.class), anyString());
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            assertEquals("Long-term memory hints. Verify current cluster state before using them.",
                    state.getContext().get("memoryContext"));
            Task task = restartTask();
            state.setExecutionId("exec-memory");
            state.setTaskPlan(new TaskPlan("exec-memory", "payment owner 是谁", List.of(task), Instant.now(), false));
            state.setCurrentTask(task);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(plannerAgent).run(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).plan(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(verifierAgent).run(any(GraphState.class));
        doAnswer(invocation -> {
            GraphState state = invocation.getArgument(0);
            state.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).executePrepared(any(GraphState.class));

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

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService
        );

        GraphState state = approvedRunnableState("exec-1");
        when(approvalService.loadState("exec-1")).thenReturn(state);
        when(responseComposer.compose(state)).thenReturn("ok");
        doAnswer(invocation -> {
            GraphState graphState = invocation.getArgument(0);
            graphState.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).executePrepared(state);

        AskService.AskExecutionResult result = service.resumeAfterApproval("exec-1");

        assertEquals("exec-1", result.executionId());
        verify(executorAgent).executePrepared(state);
        verify(approvalService).clearState("exec-1");
        verify(executionAuditService).recordGraphExecution(any(), any(), any());
    }

    @Test
    void shouldRejectResumeWhenNotApproved() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService
        );

        GraphState state = approvedRunnableState("exec-2");
        state.setFinalApprovalDecision(ApprovalDecision.PENDING);
        when(approvalService.loadState("exec-2")).thenReturn(state);

        assertThrows(IllegalStateException.class, () -> service.resumeAfterApproval("exec-2"));
        verify(executorAgent, never()).executePrepared(any());
    }

    @Test
    void shouldDecideAndResume() {
        PlannerAgent plannerAgent = mock(PlannerAgent.class);
        VerifierAgent verifierAgent = mock(VerifierAgent.class);
        ExecutorAgent executorAgent = mock(ExecutorAgent.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        ResponseComposer responseComposer = mock(ResponseComposer.class);
        ExecutionAuditService executionAuditService = mock(ExecutionAuditService.class);

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService
        );

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
                List.of("high-risk")
        );
        when(approvalService.decide("exec-3", ApprovalDecision.APPROVED, "ok", "tester"))
                .thenReturn(approvalRequest);
        when(approvalService.loadState("exec-3")).thenReturn(state);
        when(responseComposer.compose(state)).thenReturn("resumed");
        doAnswer(invocation -> {
            GraphState graphState = invocation.getArgument(0);
            graphState.setStatus(GraphStatus.SUCCESS);
            return null;
        }).when(executorAgent).executePrepared(state);

        AskService.ApprovalExecutionResult result = service.decideAndResume("exec-3", ApprovalDecision.APPROVED, "ok", "tester");

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

        AskService service = new AskService(
                plannerAgent,
                verifierAgent,
                executorAgent,
                approvalService,
                responseComposer,
                executionAuditService
        );

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
                List.of("high-risk")
        );
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
                new SopReference("SOP-RESTART_SERVICE", "restart", "v1", "rag:sop")
        );
    }
}
