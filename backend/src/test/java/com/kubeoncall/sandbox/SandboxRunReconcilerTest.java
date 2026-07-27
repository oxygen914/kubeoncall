package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;

class SandboxRunReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Test
    void preservesRecoverableRunWhenControllerIsUnavailable() {
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        SandboxRunRecord dispatching = run(SandboxRunStatus.DISPATCHING, 1L, NOW.plusSeconds(300));
        when(repository.claimForReconciliation(anyString(), eq(NOW), any())).thenReturn(Optional.of(dispatching));
        when(controller.status(dispatching))
                .thenThrow(new SandboxControllerClientException("CONTROLLER_TIMEOUT", true, 0));

        SandboxRunReconciler.Outcome outcome =
                reconciler(repository, controller).runOnce();

        assertThat(outcome).isEqualTo(SandboxRunReconciler.Outcome.DEFERRED);
        verify(repository, never())
                .fail(anyString(), anyString(), anyLong(), anyLong(), any(), anyString(), anyString(), any());
        verify(repository, never()).complete(anyString(), anyString(), anyLong(), anyLong(), any(), any());
        verify(repository).releaseReconciliationClaim(eq("sbx_abc"), anyString(), eq(1L));
    }

    @Test
    void timeoutWinsBeforeLateControllerSuccessAndRequestsCancellation() {
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        SandboxRunRecord expired = run(SandboxRunStatus.DISPATCHING, 1L, NOW.minusSeconds(1));
        when(repository.claimForReconciliation(anyString(), eq(NOW), any())).thenReturn(Optional.of(expired));
        when(repository.fail(
                        eq("sbx_abc"),
                        anyString(),
                        eq(1L),
                        eq(1L),
                        eq(SandboxRunStatus.TIMED_OUT),
                        eq("SANDBOX_RUN_EXPIRED"),
                        anyString(),
                        eq(NOW)))
                .thenReturn(true);

        SandboxRunReconciler.Outcome outcome =
                reconciler(repository, controller).runOnce();

        assertThat(outcome).isEqualTo(SandboxRunReconciler.Outcome.TIMED_OUT);
        verify(controller).cancel(expired);
        verify(controller, never()).status(expired);
    }

    @Test
    void convergesSucceededControllerRunThroughCollectingWithoutInliningLogs() {
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        SandboxRunRecord dispatching = run(SandboxRunStatus.DISPATCHING, 1L, NOW.plusSeconds(300));
        SandboxRunRecord running = run(SandboxRunStatus.RUNNING, 1L, NOW.plusSeconds(300), 2L);
        SandboxRunRecord collecting = run(SandboxRunStatus.COLLECTING, 1L, NOW.plusSeconds(300), 3L);
        when(repository.claimForReconciliation(anyString(), eq(NOW), any())).thenReturn(Optional.of(dispatching));
        when(controller.status(dispatching))
                .thenReturn(new SandboxControllerClient.ControllerStatus("sbx_abc", "SUCCEEDED", true));
        when(repository.transitionRunStatus(
                        eq("sbx_abc"), anyString(), eq(1L), eq(1L), eq(SandboxRunStatus.RUNNING), eq(NOW)))
                .thenReturn(true);
        when(repository.findByPublicId("sbx_abc")).thenReturn(Optional.of(running), Optional.of(collecting));
        when(repository.transitionRunStatus(
                        eq("sbx_abc"), anyString(), eq(1L), eq(2L), eq(SandboxRunStatus.COLLECTING), eq(NOW)))
                .thenReturn(true);
        when(controller.collect(collecting))
                .thenReturn(new SandboxControllerClient.CollectedResult("sbx_abc", "SUCCEEDED", 0, "", "", true));
        when(repository.complete(eq("sbx_abc"), anyString(), eq(1L), eq(3L), any(), eq(NOW)))
                .thenReturn(true);

        SandboxRunReconciler.Outcome outcome =
                reconciler(repository, controller).runOnce();

        assertThat(outcome).isEqualTo(SandboxRunReconciler.Outcome.SUCCEEDED);
        verify(repository, never()).createArtifact(any());
    }

    @Test
    void retriesTerminalCleanupUntilControllerReportsNoRemainingJob() {
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        SandboxRunRecord pendingCleanup =
                run(SandboxRunStatus.SUCCEEDED, 2L, NOW.plusSeconds(300), 1L, SandboxCleanupStatus.PENDING);
        SandboxRunRecord runningCleanup =
                run(SandboxRunStatus.SUCCEEDED, 2L, NOW.plusSeconds(300), 2L, SandboxCleanupStatus.RUNNING);
        when(repository.claimForReconciliation(anyString(), eq(NOW), any())).thenReturn(Optional.empty());
        when(repository.claimCleanupForReconciliation(anyString(), eq(NOW), any()))
                .thenReturn(Optional.of(pendingCleanup));
        when(repository.transitionCleanupStatus(eq("sbx_abc"), eq(1L), eq(SandboxCleanupStatus.RUNNING), eq(NOW)))
                .thenReturn(true);
        when(repository.findByPublicId("sbx_abc")).thenReturn(Optional.of(runningCleanup));
        when(controller.status(runningCleanup))
                .thenReturn(new SandboxControllerClient.ControllerStatus("sbx_abc", "UNKNOWN", false));
        when(repository.transitionCleanupStatus(eq("sbx_abc"), eq(2L), eq(SandboxCleanupStatus.SUCCEEDED), eq(NOW)))
                .thenReturn(true);

        SandboxRunReconciler.Outcome outcome =
                reconciler(repository, controller).runOnce();

        assertThat(outcome).isEqualTo(SandboxRunReconciler.Outcome.CLEANED);
        verify(repository).releaseCleanupClaim(eq("sbx_abc"), anyString(), eq(2L));
    }

    private static SandboxRunReconciler reconciler(
            SandboxRunRepository repository, SandboxControllerClient controller) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSandbox().setControllerReadTimeoutMillis(5000);
        return new SandboxRunReconciler(
                repository,
                controller,
                mock(SandboxArtifactStore.class),
                properties,
                mock(OutboxWriter.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SandboxRunRecord run(SandboxRunStatus status, long fence, Instant expiresAt) {
        return run(status, fence, expiresAt, 1L);
    }

    private static SandboxRunRecord run(SandboxRunStatus status, long fence, Instant expiresAt, long version) {
        return run(status, fence, expiresAt, version, SandboxCleanupStatus.NOT_REQUIRED);
    }

    private static SandboxRunRecord run(
            SandboxRunStatus status, long fence, Instant expiresAt, long version, SandboxCleanupStatus cleanupStatus) {
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "pod-inspect",
                "v1",
                "registry.example/pod-inspect@sha256:" + "a".repeat(64),
                status,
                cleanupStatus,
                null,
                0,
                SandboxRiskLevel.LOW,
                "usr_1",
                "key",
                Map.of(),
                Map.of(),
                null,
                null,
                "sbx_abc",
                "reconciler",
                NOW.plusSeconds(30),
                fence,
                1,
                3,
                expiresAt,
                "req_abc",
                null,
                version,
                NOW,
                null,
                NOW,
                NOW);
    }
}
