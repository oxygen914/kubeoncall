package com.kubeoncall.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import com.kubeoncall.observability.CorrelationContext;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskHandler.HandlerResult;
import com.kubeoncall.task.worker.AsyncTaskWorker.LeaseGuard;
import com.kubeoncall.task.worker.AsyncTaskWorker.Outcome;
import com.kubeoncall.task.worker.AsyncTaskWorker.RunResult;

class AsyncTaskWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-21T02:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OWNER = "worker-a";
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(2);
    private static final Set<String> TASK_TYPES = Set.of("ASK_EXECUTION");

    @Test
    void completesSuccessfulTaskAfterLeaseValidation() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 1, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenReturn(new HandlerResult(Map.of("answer", "ok")));
        when(repository.complete(task.publicId(), OWNER, task.fencingToken(), Map.of("answer", "ok"), NOW))
                .thenReturn(true);

        RunResult result = worker(repository, List.of(handler), heartbeat).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(heartbeat.requireValidCalls).isEqualTo(1);
        verify(repository).complete(task.publicId(), OWNER, task.fencingToken(), Map.of("answer", "ok"), NOW);
        assertThat(heartbeat.closed).isTrue();
    }

    @Test
    void notifiesLifecycleListenerOnlyAfterAcceptedDurableTransition() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 1, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        AsyncTaskLifecycleListener listener = mock(AsyncTaskLifecycleListener.class);
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenReturn(new HandlerResult(Map.of("answer", "ok")));
        when(repository.complete(task.publicId(), OWNER, task.fencingToken(), Map.of("answer", "ok"), NOW))
                .thenReturn(true);

        RunResult result = worker(repository, List.of(handler), heartbeat, List.of(listener))
                .runOnce();

        verify(listener).onTransition(task, result);
    }

    @Test
    void doesNotNotifyLifecycleListenerWhenOwnershipFenceRejectsTransition() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 1, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        AsyncTaskLifecycleListener listener = mock(AsyncTaskLifecycleListener.class);
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenReturn(new HandlerResult(Map.of("answer", "ok")));
        when(repository.complete(task.publicId(), OWNER, task.fencingToken(), Map.of("answer", "ok"), NOW))
                .thenReturn(false);

        RunResult result = worker(repository, List.of(handler), heartbeat, List.of(listener))
                .runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.LEASE_LOST);
        verifyNoInteractions(listener);
    }

    @Test
    void schedulesExponentialRetryWhenHandlerFails() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 3, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenThrow(new IllegalStateException("model unavailable"));
        Instant nextAttemptAt = NOW.plusSeconds(8);
        when(repository.retry(
                        task.publicId(),
                        OWNER,
                        task.fencingToken(),
                        "HANDLER_FAILURE",
                        "IllegalStateException: model unavailable",
                        nextAttemptAt,
                        NOW))
                .thenReturn(true);

        RunResult result = worker(repository, List.of(handler), heartbeat).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(result.nextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(heartbeat.requireValidCalls).isEqualTo(1);
        verify(repository, never()).deadLetter(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void deadLettersExhaustedTask() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 5, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenThrow(new IllegalArgumentException("invalid input"));
        when(repository.deadLetter(
                        task.publicId(),
                        OWNER,
                        task.fencingToken(),
                        "HANDLER_FAILURE",
                        "IllegalArgumentException: invalid input",
                        NOW))
                .thenReturn(true);

        RunResult result = worker(repository, List.of(handler), heartbeat).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.DEAD_LETTERED);
        verify(repository, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void unknownTaskTypeUsesNormalRetryPolicy() {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("UNREGISTERED", 1, 3);
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        Instant nextAttemptAt = NOW.plusSeconds(2);
        when(repository.retry(
                        task.publicId(),
                        OWNER,
                        task.fencingToken(),
                        "UNKNOWN_TASK_TYPE",
                        "No async task handler is registered for task type UNREGISTERED",
                        nextAttemptAt,
                        NOW))
                .thenReturn(true);

        RunResult result = worker(repository, List.of(), heartbeat).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_TASK_TYPE");
    }

    @Test
    void leaseLossPreventsStaleOwnerFromWritingTerminalState() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 1, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        TestLeaseGuard heartbeat = new TestLeaseGuard(false);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenReturn(new HandlerResult(Map.of("answer", "ok")));
        RunResult result = worker(repository, List.of(handler), heartbeat).runOnce();

        assertThat(result.outcome()).isEqualTo(Outcome.LEASE_LOST);
        verify(repository, never()).complete(any(), any(), anyLong(), any(), any());
        verify(repository, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
        verify(repository, never()).deadLetter(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void restoresTaskCorrelationMdcForHandlerAndCleansWorkerScope() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 1, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class))).thenAnswer(invocation -> {
            observed.set(MDC.getCopyOfContextMap());
            return new HandlerResult(Map.of("answer", "ok"));
        });
        when(repository.complete(task.publicId(), OWNER, task.fencingToken(), Map.of("answer", "ok"), NOW))
                .thenReturn(true);
        MDC.put("outer", "preserved");
        try {
            worker(repository, List.of(handler), heartbeat).runOnce();

            assertThat(observed.get())
                    .containsEntry(CorrelationContext.REQUEST_ID, "req_123")
                    .containsEntry(CorrelationContext.TRACE_ID, "trace_123")
                    .containsEntry(CorrelationContext.TASK_ID, "tsk_123")
                    .containsEntry(CorrelationContext.TASK_TYPE, "ASK_EXECUTION")
                    .containsEntry(CorrelationContext.ROUTE, "worker:async-task");
            assertThat(MDC.getCopyOfContextMap()).containsOnly(Map.entry("outer", "preserved"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void redactsSecretsBeforePersistingTaskFailureSummary() throws Exception {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        AsyncTaskRecord task = task("ASK_EXECUTION", 1, 5);
        AsyncTaskHandler handler = handler(task.taskType());
        TestLeaseGuard heartbeat = new TestLeaseGuard(true);
        when(repository.claimNext(OWNER, NOW, LEASE, TASK_TYPES)).thenReturn(Optional.of(task));
        when(handler.handle(any(AsyncTaskContext.class)))
                .thenThrow(
                        new IllegalStateException("Authorization: Bearer abcdefghijklmnopqrstuvwxyz password=hunter2"));
        when(repository.retry(
                        eq(task.publicId()),
                        eq(OWNER),
                        eq(task.fencingToken()),
                        eq("HANDLER_FAILURE"),
                        anyString(),
                        eq(NOW.plus(INITIAL_BACKOFF)),
                        eq(NOW)))
                .thenReturn(true);

        RunResult result = worker(repository, List.of(handler), heartbeat).runOnce();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(repository)
                .retry(
                        eq(task.publicId()),
                        eq(OWNER),
                        eq(task.fencingToken()),
                        eq("HANDLER_FAILURE"),
                        summary.capture(),
                        eq(NOW.plus(INITIAL_BACKOFF)),
                        eq(NOW));
        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(summary.getValue())
                .contains("Bearer [REDACTED]")
                .contains("password=[REDACTED]")
                .doesNotContain("hunter2")
                .doesNotContain("abcdefghijklmnopqrstuvwxyz");
    }

    private static AsyncTaskWorker worker(
            AsyncTaskRepository repository, List<? extends AsyncTaskHandler> handlers, LeaseGuard heartbeat) {
        return worker(repository, handlers, heartbeat, List.of());
    }

    private static AsyncTaskWorker worker(
            AsyncTaskRepository repository,
            List<? extends AsyncTaskHandler> handlers,
            LeaseGuard heartbeat,
            List<AsyncTaskLifecycleListener> lifecycleListeners) {
        return new AsyncTaskWorker(
                repository,
                new AsyncTaskHandlerRegistry(handlers),
                CLOCK,
                OWNER,
                LEASE,
                INITIAL_BACKOFF,
                MAX_BACKOFF,
                TASK_TYPES,
                (leaseTtl, renewLease, threadName) -> heartbeat,
                lifecycleListeners);
    }

    private static AsyncTaskHandler handler(String taskType) {
        AsyncTaskHandler handler = mock(AsyncTaskHandler.class);
        when(handler.taskType()).thenReturn(taskType);
        return handler;
    }

    private static AsyncTaskRecord task(String taskType, int attempt, int maxAttempts) {
        return new AsyncTaskRecord(
                42L,
                "tsk_123",
                taskType,
                "workflow_execution",
                "wfe_123",
                "dedupe-123",
                "RUNNING",
                "execute",
                10,
                Map.of("question", "why"),
                Map.of(),
                null,
                null,
                OWNER,
                NOW.plus(LEASE),
                7L,
                attempt,
                maxAttempts,
                NOW,
                NOW,
                null,
                "req_123",
                "trace_123",
                2L,
                NOW.minusSeconds(60),
                NOW);
    }

    private static final class TestLeaseGuard implements LeaseGuard {

        private final boolean valid;
        private int requireValidCalls;
        private boolean closed;

        private TestLeaseGuard(boolean valid) {
            this.valid = valid;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void requireValid(String message) {
            requireValidCalls++;
            if (!valid) {
                throw new IllegalStateException("lease expired");
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
