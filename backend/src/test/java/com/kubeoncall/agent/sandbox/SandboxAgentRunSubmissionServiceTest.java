package com.kubeoncall.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.sandbox.DiagnosticEvidenceArtifactService;
import com.kubeoncall.sandbox.SandboxRunCommandService;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.SandboxRunRepository;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;

class SandboxAgentRunSubmissionServiceTest {

    @Test
    void createsFixedDiagnosticRunThenStoresItsRequiredEvidenceArtifact() {
        SandboxRunCommandService commands = mock(SandboxRunCommandService.class);
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        DiagnosticEvidenceArtifactService artifacts = mock(DiagnosticEvidenceArtifactService.class);
        when(commands.create(any(), any(IdempotencyService.IdempotencyScope.class), anyString()))
                .thenReturn(SandboxRunCommandService.CommandResult.executed(Map.of("id", "sbx_abc"), 202));
        when(repository.findByPublicId("sbx_abc")).thenReturn(java.util.Optional.of(run()));
        SandboxAgentRunSubmissionService service =
                new SandboxAgentRunSubmissionService(commands, repository, artifacts);

        var result = service.submit(
                state(), task(), new SandboxRoutingPolicy.Decision(SandboxRunMode.FIXED_DIAGNOSTIC, "test"));

        assertThat(result.runId()).isEqualTo("sbx_abc");
        verify(commands).create(any(), any(), anyString());
        verify(artifacts).store(any(SandboxRunRecord.class), any());
    }

    @Test
    void neverCreatesUnsupportedAutomaticModeWithoutItsOwnInputBuilder() {
        SandboxRunCommandService commands = mock(SandboxRunCommandService.class);
        SandboxAgentRunSubmissionService service = new SandboxAgentRunSubmissionService(
                commands, mock(SandboxRunRepository.class), mock(DiagnosticEvidenceArtifactService.class));

        var result = service.submit(
                state(), task(), new SandboxRoutingPolicy.Decision(SandboxRunMode.GENERATED_CODE, "test"));

        assertThat(result.submitted()).isFalse();
        verify(commands, never()).create(any(), any(), anyString());
    }

    private static GraphState state() {
        GraphState state = new GraphState();
        state.setExecutionId("exe_abc");
        state.getContext().put("workflowActor", Map.of("userId", 1L, "publicId", "usr_abc", "displayName", "Operator"));
        state.addObservation("timeout observed");
        return state;
    }

    private static Task task() {
        return new Task("task_abc", "diagnose", TaskType.RESTART_SERVICE, RiskLevel.MEDIUM, "api", Map.of(), null);
    }

    private static SandboxRunRecord run() {
        Instant now = Instant.now();
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                "exe_abc",
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "log-pattern-analysis",
                "v1",
                "image",
                SandboxRunStatus.PENDING,
                SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                SandboxRiskLevel.LOW,
                "usr_abc",
                "key",
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                3,
                now.plusSeconds(300),
                "req",
                null,
                1,
                null,
                null,
                now,
                now);
    }
}
