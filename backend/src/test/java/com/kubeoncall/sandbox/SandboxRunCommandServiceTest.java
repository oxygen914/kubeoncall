package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.sandbox.policy.SandboxExecutionPolicy;
import com.kubeoncall.sandbox.policy.SandboxResourceLimits;
import com.kubeoncall.sandbox.policy.SandboxToolCatalog;
import com.kubeoncall.sandbox.policy.SandboxToolSpec;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.task.AsyncTaskRepository;

class SandboxRunCommandServiceTest {

    @Test
    void createsBoundedDispatchTaskWithSameRunIdInCommandFlow() {
        SandboxRunRepository runRepository = mock(SandboxRunRepository.class);
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        SandboxToolCatalog catalog = mock(SandboxToolCatalog.class);
        OperationAuditWriter audit = mock(OperationAuditWriter.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        when(runRepository.isAvailable()).thenReturn(true);
        when(taskRepository.isAvailable()).thenReturn(true);
        when(audit.isAvailable()).thenReturn(true);
        when(outbox.isAvailable()).thenReturn(true);
        when(idempotency.isAvailable()).thenReturn(true);
        when(idempotency.begin(any(), eq("dispatch-key"), any())).thenReturn(IdempotencyService.BeginResult.execute());
        SandboxToolSpec tool = new SandboxToolSpec(
                "pod-inspect",
                "v1",
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "registry.example/pod-inspect@sha256:" + "a".repeat(64),
                "/tool",
                new SandboxResourceLimits("100m", "128Mi", "256Mi", 1024, 1024, 1024, 1024, 60, 60),
                SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                "schemas/input.json",
                "schemas/output.json");
        when(catalog.find("pod-inspect", "v1", SandboxRunMode.FIXED_DIAGNOSTIC)).thenReturn(Optional.of(tool));
        SandboxRunRecord created = run();
        when(runRepository.create(any())).thenReturn(created);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setFixedDiagnostic(true);
        SandboxRunCommandService service = new SandboxRunCommandService(
                provider(runRepository),
                provider(taskRepository),
                provider(catalog),
                properties,
                new SandboxExecutionPolicy(),
                audit,
                outbox,
                idempotency,
                new ObjectMapper(),
                metrics());

        SandboxRunCommandService.CommandResult result = service.create(
                new SandboxRunCommandService.CreateCommand(
                        SandboxRunMode.FIXED_DIAGNOSTIC,
                        "pod-inspect",
                        "v1",
                        null,
                        null,
                        false,
                        false,
                        Instant.now().plusSeconds(300),
                        7L,
                        "usr_7",
                        "operator",
                        "req_7",
                        null,
                        "127.0.0.1",
                        "test"),
                new IdempotencyService.IdempotencyScope("USER", "usr_7", "sandbox.create"),
                "dispatch-key");

        assertThat(result.httpStatus()).isEqualTo(202);
        ArgumentCaptor<SandboxRunRepository.CreateRun> runCommand =
                ArgumentCaptor.forClass(SandboxRunRepository.CreateRun.class);
        verify(runRepository).create(runCommand.capture());
        assertThat(runCommand.getValue().maxAttempts()).isEqualTo(3);
        ArgumentCaptor<AsyncTaskRepository.CreateTask> taskCommand =
                ArgumentCaptor.forClass(AsyncTaskRepository.CreateTask.class);
        verify(taskRepository).create(taskCommand.capture());
        assertThat(taskCommand.getValue().publicId()).isEqualTo("tsk_sbx_abc");
        assertThat(taskCommand.getValue().taskType()).isEqualTo(SandboxDispatchTaskHandler.TASK_TYPE);
        assertThat(taskCommand.getValue().resourcePublicId()).isEqualTo("sbx_abc");
        assertThat(taskCommand.getValue().dedupeKey()).isEqualTo("sandbox-dispatch:sbx_abc");
        assertThat(taskCommand.getValue().maxAttempts()).isEqualTo(3);
        verify(idempotency).succeed(any(), eq("dispatch-key"), eq(202), any(), eq("sandbox-run"), eq("sbx_abc"));
    }

    private static SandboxRunRecord run() {
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "pod-inspect",
                "v1",
                "registry.example/pod-inspect@sha256:" + "a".repeat(64),
                SandboxRunStatus.PENDING,
                SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                SandboxRiskLevel.LOW,
                "usr_7",
                "idempotency",
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                0L,
                0,
                3,
                Instant.now().plusSeconds(300),
                "req_7",
                null,
                1L,
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static KubeOnCallMetricsService metrics() {
        return new KubeOnCallMetricsService(provider(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }
}
