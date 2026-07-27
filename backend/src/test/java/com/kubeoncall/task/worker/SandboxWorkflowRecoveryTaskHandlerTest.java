package com.kubeoncall.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kubeoncall.agent.executor.ExecutorAgent;
import com.kubeoncall.agent.sandbox.SandboxProductionStateRecheckService;
import com.kubeoncall.agent.sandbox.SandboxTerminalOutboxHandler;
import com.kubeoncall.agent.sandbox.SandboxWorkflowRecoveryService;
import com.kubeoncall.agent.sandbox.SandboxWorkflowRecoveryTaskHandler;
import com.kubeoncall.agent.verifier.VerifierAgent;
import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.SandboxRunRepository;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.runtime.WorkflowTaskResultCoordinator;

class SandboxWorkflowRecoveryTaskHandlerTest {

    @Test
    void storesUntrustedEvidenceWithoutResumingProductionExecution() {
        SandboxRunRepository runs = mock(SandboxRunRepository.class);
        ApprovalService approvals = mock(ApprovalService.class);
        SandboxProductionStateRecheckService productionRecheck = mock(SandboxProductionStateRecheckService.class);
        ExecutorAgent executor = mock(ExecutorAgent.class);
        VerifierAgent verifier = mock(VerifierAgent.class);
        WorkflowTaskResultCoordinator coordinator = mock(WorkflowTaskResultCoordinator.class);
        GraphState state = new GraphState();
        state.getContext().put("sandboxRoute", Map.of("runId", "sbx_123"));
        state.setCurrentTask(new com.kubeoncall.domain.task.Task(
                "task-123",
                "restart",
                com.kubeoncall.domain.task.TaskType.RESTART_SERVICE,
                com.kubeoncall.domain.task.RiskLevel.HIGH,
                "api",
                Map.of(),
                null));
        when(runs.findByPublicId("sbx_123")).thenReturn(Optional.of(run("wfe_123", SandboxRunStatus.SUCCEEDED)));
        when(approvals.loadState("wfe_123")).thenReturn(state);
        when(coordinator.markSandboxRecovering(org.mockito.ArgumentMatchers.any()))
                .thenReturn(execution("RUNNING"));
        when(productionRecheck.recheck(state))
                .thenReturn(new SandboxProductionStateRecheckService.Recheck(true, Map.of("status", "CURRENT")));
        doAnswer(invocation -> {
                    state.setStatus(com.kubeoncall.domain.graph.GraphStatus.SUCCESS);
                    return state;
                })
                .when(executor)
                .plan(state);
        doAnswer(invocation -> {
                    state.setStatus(com.kubeoncall.domain.graph.GraphStatus.PAUSED);
                    return state;
                })
                .when(verifier)
                .run(state);
        when(coordinator.finalizeResult(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new WorkflowTaskResultCoordinator.FinalizationResult(
                        "wfe_123", "WAITING_APPROVAL", null, "apr_123", false));
        SandboxWorkflowRecoveryTaskHandler handler =
                handler(runs, approvals, productionRecheck, executor, verifier, coordinator);

        AsyncTaskHandler.HandlerResult result = handler.handle(context("sbx_123", "wfe_123"));

        assertThat(result.result())
                .containsEntry("accepted", true)
                .containsEntry("requiresVerifier", true)
                .containsEntry("requiresProductionRecheck", true)
                .containsEntry("workflowStatus", "WAITING_APPROVAL");
        assertThat(state.getContext().get("sandboxRemediationProposal")).isInstanceOf(Map.class);
        verify(approvals).saveState(state);
        verify(executor).plan(state);
        verify(verifier).run(state);
        verify(approvals, never())
                .decide(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsRunWhoseExecutionDoesNotMatchRecoveryTask() {
        SandboxRunRepository runs = mock(SandboxRunRepository.class);
        ApprovalService approvals = mock(ApprovalService.class);
        when(runs.findByPublicId("sbx_123")).thenReturn(Optional.of(run("wfe_other", SandboxRunStatus.SUCCEEDED)));
        SandboxWorkflowRecoveryTaskHandler handler = handler(
                runs,
                approvals,
                mock(SandboxProductionStateRecheckService.class),
                mock(ExecutorAgent.class),
                mock(VerifierAgent.class),
                mock(WorkflowTaskResultCoordinator.class));

        assertThatThrownBy(() -> handler.handle(context("sbx_123", "wfe_123")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        verify(approvals, never()).loadState(org.mockito.ArgumentMatchers.anyString());
        verify(approvals, never()).saveState(org.mockito.ArgumentMatchers.any());
    }

    private static SandboxWorkflowRecoveryTaskHandler handler(
            SandboxRunRepository runs,
            ApprovalService approvals,
            SandboxProductionStateRecheckService productionRecheck,
            ExecutorAgent executor,
            VerifierAgent verifier,
            WorkflowTaskResultCoordinator coordinator) {
        return new SandboxWorkflowRecoveryTaskHandler(
                runs,
                approvals,
                new SandboxWorkflowRecoveryService(),
                productionRecheck,
                executor,
                verifier,
                coordinator);
    }

    private static WorkflowExecutionRecord execution(String status) {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        return new WorkflowExecutionRecord(
                1L, "wfe_123", "ASK", "MANUAL", null, "dedupe", status, "HIGH", "summary", null, null, null, "USER", 1L,
                null, "req_123", null, null, now, null, 1L, now, now);
    }

    private static AsyncTaskContext context(String runId, String executionId) {
        return new AsyncTaskContext(task(runId, executionId), "worker-a", () -> true);
    }

    private static AsyncTaskRecord task(String runId, String executionId) {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        return new AsyncTaskRecord(
                1L,
                "tsk_123",
                SandboxTerminalOutboxHandler.TASK_TYPE,
                "sandbox-run",
                runId,
                "sandbox-workflow-recovery:" + runId,
                "RUNNING",
                "queued",
                0,
                Map.of("runId", runId, "executionId", executionId),
                Map.of(),
                null,
                null,
                "worker-a",
                now.plusSeconds(60),
                2L,
                1,
                5,
                now,
                now,
                null,
                "req_123",
                null,
                1L,
                now,
                now);
    }

    private static SandboxRunRecord run(String executionId, SandboxRunStatus status) {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        return new SandboxRunRecord(
                1L,
                "sbx_123",
                executionId,
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "tool",
                "v1",
                "image",
                status,
                SandboxCleanupStatus.PENDING,
                null,
                100,
                SandboxRiskLevel.LOW,
                "usr_123",
                "idempotency",
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                0L,
                1,
                3,
                now.plusSeconds(60),
                "req_123",
                null,
                1L,
                now,
                now,
                now,
                now);
    }
}
