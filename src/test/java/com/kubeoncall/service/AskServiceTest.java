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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
        verify(executorAgent).plan(any());
        verify(verifierAgent).run(any());
        verify(executorAgent).executePrepared(any());
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
