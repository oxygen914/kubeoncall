package com.kubeoncall.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;

class SandboxWorkflowRecoveryServiceTest {

    @Test
    void recordsTerminalResultAsUntrustedProposalWithoutAnExecutableAction() {
        GraphState state = new GraphState();
        state.getContext().put("sandboxRoute", Map.of("runId", "sbx_abc"));

        var result = new SandboxWorkflowRecoveryService().recover(state, run("sbx_abc"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.evidence()).containsEntry("classification", "UNTRUSTED");
        assertThat(result.proposal())
                .containsEntry("suggestedAction", "NONE")
                .containsEntry("requiresProductionRecheck", true);
    }

    @Test
    void ignoresTerminalRunThatDoesNotOwnThePausedRoute() {
        GraphState state = new GraphState();
        state.getContext().put("sandboxRoute", Map.of("runId", "sbx_expected"));

        assertThat(new SandboxWorkflowRecoveryService()
                        .recover(state, run("sbx_other"))
                        .accepted())
                .isFalse();
        assertThat(state.getContext()).doesNotContainKey("sandboxRemediationProposal");
    }

    private static SandboxRunRecord run(String id) {
        Instant now = Instant.now();
        return new SandboxRunRecord(
                1L,
                id,
                "exe_abc",
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "tool",
                "v1",
                "image",
                SandboxRunStatus.SUCCEEDED,
                SandboxCleanupStatus.PENDING,
                null,
                0,
                SandboxRiskLevel.LOW,
                "usr",
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
                now.plusSeconds(1),
                "req",
                null,
                1,
                now,
                now,
                now,
                now);
    }
}
