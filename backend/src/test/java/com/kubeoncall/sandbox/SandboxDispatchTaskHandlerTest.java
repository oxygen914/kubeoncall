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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskHandlerRegistry;
import com.kubeoncall.task.worker.AsyncTaskWorker;
import com.kubeoncall.task.worker.AsyncTaskWorker.Outcome;

class SandboxDispatchTaskHandlerTest {

    private static final String OWNER = "sandbox-worker";

    @Test
    void claimsPendingRunMarksDispatchingAndReturnsWithoutPolling() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        SandboxRunRepository runRepository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        AsyncTaskRecord task = task();
        SandboxRunRecord pending = run(SandboxRunStatus.PENDING, null, 0L, 1L);
        SandboxRunRecord claimed = run(SandboxRunStatus.PENDING, OWNER, 1L, 2L);
        SandboxRunRecord dispatching = run(SandboxRunStatus.DISPATCHING, OWNER, 1L, 3L);
        when(taskRepository.claimNext(eq(OWNER), any(), any(), eq(Set.of(SandboxDispatchTaskHandler.TASK_TYPE))))
                .thenReturn(Optional.of(task));
        when(runRepository.findByPublicId("sbx_abc")).thenReturn(Optional.of(pending), Optional.of(dispatching));
        when(runRepository.claimByPublicId(eq("sbx_abc"), eq(OWNER), any(), any()))
                .thenReturn(Optional.of(claimed));
        when(runRepository.markDispatching(eq("sbx_abc"), eq(OWNER), eq(1L), eq(2L), eq("sbx_abc"), any()))
                .thenReturn(true);
        when(controller.dispatch(dispatching))
                .thenReturn(new SandboxControllerClient.DispatchResult("sbx_abc", "PENDING", 200));
        when(taskRepository.complete(eq(task.publicId()), eq(OWNER), eq(task.fencingToken()), any(), any()))
                .thenReturn(true);

        AsyncTaskWorker.RunResult result =
                worker(taskRepository, handler(runRepository, controller)).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        verify(controller).dispatch(dispatching);
        verify(runRepository, never())
                .transitionRunStatus(anyString(), anyString(), anyLong(), anyLong(), any(), any());
        verify(runRepository, never()).complete(anyString(), anyString(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void permanentControllerRejectionFailsTaskWithoutRetrying() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        SandboxRunRepository runRepository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        AsyncTaskRecord task = task();
        SandboxRunRecord dispatching = run(SandboxRunStatus.DISPATCHING, OWNER, 1L, 3L);
        when(taskRepository.claimNext(eq(OWNER), any(), any(), eq(Set.of(SandboxDispatchTaskHandler.TASK_TYPE))))
                .thenReturn(Optional.of(task));
        when(runRepository.findByPublicId("sbx_abc")).thenReturn(Optional.of(dispatching));
        when(controller.dispatch(dispatching))
                .thenThrow(new SandboxControllerClientException("CONTROLLER_HTTP_400", false, 400));
        when(runRepository.fail(
                        eq("sbx_abc"),
                        eq(OWNER),
                        eq(1L),
                        eq(3L),
                        eq(SandboxRunStatus.FAILED),
                        eq("CONTROLLER_HTTP_400"),
                        anyString(),
                        any()))
                .thenReturn(true);
        when(taskRepository.fail(
                        eq(task.publicId()),
                        eq(OWNER),
                        eq(task.fencingToken()),
                        eq("CONTROLLER_HTTP_400"),
                        anyString(),
                        any()))
                .thenReturn(true);

        AsyncTaskWorker.RunResult result =
                worker(taskRepository, handler(runRepository, controller)).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        verify(taskRepository, never())
                .retry(anyString(), anyString(), anyLong(), anyString(), anyString(), any(), any());
        verify(runRepository)
                .fail(
                        eq("sbx_abc"),
                        eq(OWNER),
                        eq(1L),
                        eq(3L),
                        eq(SandboxRunStatus.FAILED),
                        eq("CONTROLLER_HTTP_400"),
                        anyString(),
                        any());
    }

    @Test
    void retriesDispatchingRunWithSameRunIdWithoutASecondClaimOrJobPoll() {
        AsyncTaskRepository taskRepository = mock(AsyncTaskRepository.class);
        SandboxRunRepository runRepository = mock(SandboxRunRepository.class);
        SandboxControllerClient controller = mock(SandboxControllerClient.class);
        AsyncTaskRecord task = task();
        SandboxRunRecord dispatching = run(SandboxRunStatus.DISPATCHING, "another-worker", 2L, 4L);
        when(taskRepository.claimNext(eq(OWNER), any(), any(), eq(Set.of(SandboxDispatchTaskHandler.TASK_TYPE))))
                .thenReturn(Optional.of(task));
        when(runRepository.findByPublicId("sbx_abc")).thenReturn(Optional.of(dispatching));
        when(controller.dispatch(dispatching))
                .thenReturn(new SandboxControllerClient.DispatchResult("sbx_abc", "PENDING", 200));
        when(taskRepository.complete(eq(task.publicId()), eq(OWNER), eq(task.fencingToken()), any(), any()))
                .thenReturn(true);

        AsyncTaskWorker.RunResult result =
                worker(taskRepository, handler(runRepository, controller)).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        verify(controller).dispatch(dispatching);
        verify(runRepository, never()).claimByPublicId(anyString(), anyString(), any(), any());
        verify(runRepository, never())
                .markDispatching(anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
    }

    private static SandboxDispatchTaskHandler handler(
            SandboxRunRepository runRepository, SandboxControllerClient controller) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSandbox().setControllerReadTimeoutMillis(5000);
        return new SandboxDispatchTaskHandler(runRepository, controller, properties);
    }

    private static AsyncTaskWorker worker(AsyncTaskRepository repository, SandboxDispatchTaskHandler handler) {
        return new AsyncTaskWorker(
                repository,
                new AsyncTaskHandlerRegistry(List.of(handler)),
                Clock.systemUTC(),
                OWNER,
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Set.of(SandboxDispatchTaskHandler.TASK_TYPE));
    }

    private static AsyncTaskRecord task() {
        return new AsyncTaskRecord(
                1L,
                "tsk_sbx_abc",
                SandboxDispatchTaskHandler.TASK_TYPE,
                "SANDBOX_RUN",
                "sbx_abc",
                "sandbox-dispatch:sbx_abc",
                "PENDING",
                "queued",
                0,
                Map.of("runId", "sbx_abc"),
                Map.of(),
                null,
                null,
                null,
                null,
                2L,
                1,
                3,
                Instant.now(),
                null,
                null,
                "req_abc",
                null,
                1L,
                Instant.now(),
                Instant.now());
    }

    private static SandboxRunRecord run(SandboxRunStatus status, String owner, long fence, long version) {
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "pod-inspect",
                "v1",
                "registry.example/tool@sha256:" + "a".repeat(64),
                status,
                SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                SandboxRiskLevel.LOW,
                "usr_1",
                "key",
                Map.of(),
                Map.of(),
                null,
                null,
                status == SandboxRunStatus.DISPATCHING ? "sbx_abc" : null,
                owner,
                Instant.now().plusSeconds(60),
                fence,
                1,
                3,
                Instant.now().plusSeconds(600),
                "req_abc",
                null,
                version,
                null,
                null,
                Instant.now(),
                Instant.now());
    }
}
