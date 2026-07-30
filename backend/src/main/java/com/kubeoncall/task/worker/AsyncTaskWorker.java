package com.kubeoncall.task.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kubeoncall.common.concurrent.LeaseHeartbeat;
import com.kubeoncall.observability.CorrelationContext;
import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskHandler.HandlerResult;

/**
 * Single-task durable worker primitive.
 *
 * <p>A scheduler or an application-managed loop may invoke {@link #runOnce()}; this class owns no
 * scheduling policy. Claiming and every state transition are short repository operations, while
 * the potentially slow handler runs without a database transaction.
 */
public final class AsyncTaskWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncTaskWorker.class);
    private static final int MAX_ERROR_SUMMARY_LENGTH = 1000;
    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private final AsyncTaskRepository repository;
    private final AsyncTaskHandlerRegistry registry;
    private final Clock clock;
    private final String ownerToken;
    private final Duration leaseDuration;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Set<String> taskTypes;
    private final HeartbeatStarter heartbeatStarter;
    private final List<AsyncTaskLifecycleListener> lifecycleListeners;

    public AsyncTaskWorker(
            AsyncTaskRepository repository,
            AsyncTaskHandlerRegistry registry,
            Clock clock,
            String ownerToken,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            Set<String> taskTypes) {
        this(
                repository,
                registry,
                clock,
                ownerToken,
                leaseDuration,
                initialBackoff,
                maxBackoff,
                taskTypes,
                (leaseTtl, renewLease, threadName) ->
                        new LeaseHeartbeatGuard(LeaseHeartbeat.start(leaseTtl, renewLease, threadName)),
                List.of());
    }

    public AsyncTaskWorker(
            AsyncTaskRepository repository,
            AsyncTaskHandlerRegistry registry,
            Clock clock,
            String ownerToken,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            Set<String> taskTypes,
            List<AsyncTaskLifecycleListener> lifecycleListeners) {
        this(
                repository,
                registry,
                clock,
                ownerToken,
                leaseDuration,
                initialBackoff,
                maxBackoff,
                taskTypes,
                (leaseTtl, renewLease, threadName) ->
                        new LeaseHeartbeatGuard(LeaseHeartbeat.start(leaseTtl, renewLease, threadName)),
                lifecycleListeners);
    }

    AsyncTaskWorker(
            AsyncTaskRepository repository,
            AsyncTaskHandlerRegistry registry,
            Clock clock,
            String ownerToken,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            Set<String> taskTypes,
            HeartbeatStarter heartbeatStarter) {
        this(
                repository,
                registry,
                clock,
                ownerToken,
                leaseDuration,
                initialBackoff,
                maxBackoff,
                taskTypes,
                heartbeatStarter,
                List.of());
    }

    AsyncTaskWorker(
            AsyncTaskRepository repository,
            AsyncTaskHandlerRegistry registry,
            Clock clock,
            String ownerToken,
            Duration leaseDuration,
            Duration initialBackoff,
            Duration maxBackoff,
            Set<String> taskTypes,
            HeartbeatStarter heartbeatStarter,
            List<AsyncTaskLifecycleListener> lifecycleListeners) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownerToken = requireText(ownerToken, "ownerToken");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
        this.maxBackoff = requirePositive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        this.taskTypes = normalizeTaskTypes(taskTypes);
        this.heartbeatStarter = Objects.requireNonNull(heartbeatStarter, "heartbeatStarter");
        this.lifecycleListeners = lifecycleListeners == null ? List.of() : List.copyOf(lifecycleListeners);
    }

    /**
     * Claims and processes at most one task.
     *
     * <p>The heartbeat renews every lease-duration/3 through {@link LeaseHeartbeat}. A lost lease
     * never becomes a retry, dead letter or success written by the stale owner.
     */
    public RunResult runOnce() {
        AsyncTaskRecord task = repository
                .claimNext(ownerToken, clock.instant(), leaseDuration, taskTypes)
                .orElse(null);
        if (task == null) {
            return RunResult.idle();
        }

        try (CorrelationContext.Scope ignored = CorrelationContext.open(correlation(task))) {
            BooleanSupplier renewLease = () -> repository.heartbeat(
                    task.publicId(), ownerToken, task.fencingToken(), clock.instant(), leaseDuration);
            try (LeaseGuard heartbeat =
                    heartbeatStarter.start(leaseDuration, renewLease, "async-task-heartbeat-" + task.publicId())) {
                Instant deadline = deadline(task);
                if (deadline != null && !clock.instant().isBefore(deadline)) {
                    return recordTerminalFailure(
                            task, heartbeat, "TIMEOUT", "Async task exceeded its execution deadline");
                }
                AsyncTaskHandler handler = registry.find(task.taskType()).orElse(null);
                if (handler == null) {
                    return recordFailure(
                            task,
                            heartbeat,
                            "UNKNOWN_TASK_TYPE",
                            "No async task handler is registered for task type " + task.taskType());
                }

                HandlerResult handlerResult;
                AsyncTaskContext context = new AsyncTaskContext(
                        task,
                        ownerToken,
                        heartbeat::isValid,
                        () -> repository.isCancelled(task.publicId()),
                        deadline,
                        clock);
                try {
                    handlerResult = handler.handle(context);
                } catch (AsyncTaskContext.TaskCancelledException cancelled) {
                    return RunResult.cancelled(task);
                } catch (AsyncTaskContext.TaskTimedOutException timedOut) {
                    return recordTerminalFailure(task, heartbeat, "TIMEOUT", safeMessage(timedOut));
                } catch (NonRetryableTaskException nonRetryable) {
                    return recordTerminalFailure(task, heartbeat, nonRetryable.errorCode(), safeMessage(nonRetryable));
                } catch (Exception exception) {
                    LOGGER.warn(
                            "Async task handler failed: taskId={}, taskType={}",
                            task.publicId(),
                            task.taskType(),
                            exception);
                    return recordFailure(
                            task,
                            heartbeat,
                            "HANDLER_FAILURE",
                            exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                }
                try {
                    context.requireValidLease();
                } catch (AsyncTaskContext.TaskCancelledException cancelled) {
                    return RunResult.cancelled(task);
                } catch (AsyncTaskContext.TaskTimedOutException timedOut) {
                    return recordTerminalFailure(task, heartbeat, "TIMEOUT", safeMessage(timedOut));
                } catch (IllegalStateException leaseLost) {
                    return RunResult.leaseLost(task, safeMessage(leaseLost));
                }

                try {
                    heartbeat.requireValid("Async task lease was lost before completion");
                } catch (IllegalStateException leaseLost) {
                    return RunResult.leaseLost(task, safeMessage(leaseLost));
                }
                boolean completed = repository.complete(
                        task.publicId(),
                        ownerToken,
                        task.fencingToken(),
                        handlerResult == null ? Map.of() : handlerResult.result(),
                        clock.instant());
                RunResult result = completed
                        ? RunResult.succeeded(task)
                        : RunResult.leaseLost(task, "Completion was rejected by the task ownership fence");
                return observeAcceptedTransition(task, result);
            }
        }
    }

    private RunResult recordFailure(AsyncTaskRecord task, LeaseGuard heartbeat, String errorCode, String errorSummary) {
        try {
            heartbeat.requireValid("Async task lease was lost before failure recording");
        } catch (IllegalStateException leaseLost) {
            return RunResult.leaseLost(task, safeMessage(leaseLost));
        }

        Instant now = clock.instant();
        String safeSummary = truncate(errorSummary);
        if (task.attempt() >= task.maxAttempts()) {
            boolean deadLettered = repository.deadLetter(
                    task.publicId(), ownerToken, task.fencingToken(), errorCode, safeSummary, now);
            RunResult result = deadLettered
                    ? RunResult.deadLettered(task, errorCode, safeSummary)
                    : RunResult.leaseLost(task, "Dead-letter transition was rejected by the task ownership fence");
            return observeAcceptedTransition(task, result);
        }

        Instant nextAttemptAt = now.plus(backoffForAttempt(task.attempt()));
        boolean retryScheduled = repository.retry(
                task.publicId(), ownerToken, task.fencingToken(), errorCode, safeSummary, nextAttemptAt, now);
        RunResult result = retryScheduled
                ? RunResult.retryScheduled(task, errorCode, safeSummary, nextAttemptAt)
                : RunResult.leaseLost(task, "Retry transition was rejected by the task ownership fence");
        return observeAcceptedTransition(task, result);
    }

    private RunResult recordTerminalFailure(
            AsyncTaskRecord task, LeaseGuard heartbeat, String errorCode, String errorSummary) {
        try {
            heartbeat.requireValid("Async task lease was lost before terminal failure recording");
        } catch (IllegalStateException leaseLost) {
            return RunResult.leaseLost(task, safeMessage(leaseLost));
        }
        boolean failed = repository.fail(
                task.publicId(), ownerToken, task.fencingToken(), errorCode, truncate(errorSummary), clock.instant());
        RunResult result = failed
                ? RunResult.failed(task, errorCode, truncate(errorSummary))
                : RunResult.leaseLost(task, "Terminal failure transition was rejected by the task ownership fence");
        return observeAcceptedTransition(task, result);
    }

    private Instant deadline(AsyncTaskRecord task) {
        Object configured = task.request().get("timeoutSeconds");
        if (configured == null || task.startedAt() == null) {
            return null;
        }
        try {
            long seconds = configured instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(String.valueOf(configured));
            if (seconds <= 0) {
                return null;
            }
            return task.startedAt()
                    .plusSeconds(Math.min(seconds, Duration.ofDays(7).toSeconds()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private RunResult observeAcceptedTransition(AsyncTaskRecord task, RunResult result) {
        if (result.outcome() == Outcome.IDLE) {
            return result;
        }
        if (result.outcome() == Outcome.LEASE_LOST) {
            for (AsyncTaskLifecycleListener listener : lifecycleListeners) {
                try {
                    listener.onLeaseLost(task, result);
                } catch (RuntimeException listenerFailure) {
                    LOGGER.warn("Async task lease-loss listener failed: taskId={}", task.publicId(), listenerFailure);
                }
            }
            return result;
        }
        for (AsyncTaskLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onTransition(task, result);
            } catch (RuntimeException listenerFailure) {
                LOGGER.warn(
                        "Async task lifecycle listener failed after durable transition: taskId={}, outcome={}",
                        task.publicId(),
                        result.outcome(),
                        listenerFailure);
            }
        }
        return result;
    }

    private Duration backoffForAttempt(int attempt) {
        int exponent = Math.max(0, attempt - 1);
        Duration delay = initialBackoff;
        for (int index = 0; index < exponent && delay.compareTo(maxBackoff) < 0; index++) {
            if (delay.compareTo(maxBackoff.dividedBy(2)) > 0) {
                return maxBackoff;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }

    private static Set<String> normalizeTaskTypes(Set<String> taskTypes) {
        if (taskTypes == null || taskTypes.isEmpty()) {
            return Set.of();
        }
        return taskTypes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        String safe = message == null || message.isBlank() ? "handler raised an exception" : message.trim();
        return REDACTOR.redactText(safe);
    }

    private static String truncate(String summary) {
        String safe = summary == null || summary.isBlank() ? "Async task failed" : REDACTOR.redactText(summary.trim());
        return safe.length() <= MAX_ERROR_SUMMARY_LENGTH ? safe : safe.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }

    private static Map<String, Object> correlation(AsyncTaskRecord task) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(CorrelationContext.REQUEST_ID, task.requestId());
        values.put(
                CorrelationContext.TRACE_ID,
                task.traceId() == null || task.traceId().isBlank() ? task.requestId() : task.traceId());
        values.put(CorrelationContext.ROUTE, "worker:async-task");
        values.put(CorrelationContext.TASK_ID, task.publicId());
        values.put(CorrelationContext.TASK_TYPE, task.taskType());
        Object userId = firstPresent(task.request(), "actorPublicId", "actorUserId", "requestedBy", "actorId");
        if (userId != null) {
            values.put(CorrelationContext.USER_ID, userId);
        }
        values.put(CorrelationContext.AUTH_METHOD, "ASYNC_TASK");
        return values;
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    @FunctionalInterface
    interface HeartbeatStarter {

        LeaseGuard start(Duration leaseTtl, BooleanSupplier renewLease, String threadName);
    }

    interface LeaseGuard extends AutoCloseable {

        boolean isValid();

        void requireValid(String message);

        @Override
        void close();
    }

    private static final class LeaseHeartbeatGuard implements LeaseGuard {

        private final LeaseHeartbeat delegate;

        private LeaseHeartbeatGuard(LeaseHeartbeat delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
        }

        @Override
        public void requireValid(String message) {
            delegate.requireValid(message);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    public enum Outcome {
        IDLE,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        LEASE_LOST
    }

    public record RunResult(
            Outcome outcome,
            String taskPublicId,
            String taskType,
            int attempt,
            String errorCode,
            String errorSummary,
            Instant nextAttemptAt) {

        private static RunResult idle() {
            return new RunResult(Outcome.IDLE, null, null, 0, null, null, null);
        }

        private static RunResult succeeded(AsyncTaskRecord task) {
            return from(task, Outcome.SUCCEEDED, null, null, null);
        }

        private static RunResult failed(AsyncTaskRecord task, String errorCode, String errorSummary) {
            return from(task, Outcome.FAILED, errorCode, errorSummary, null);
        }

        private static RunResult cancelled(AsyncTaskRecord task) {
            return from(task, Outcome.CANCELLED, "CANCELLED", "Cancelled by operator", null);
        }

        private static RunResult retryScheduled(
                AsyncTaskRecord task, String errorCode, String errorSummary, Instant nextAttemptAt) {
            return from(task, Outcome.RETRY_SCHEDULED, errorCode, errorSummary, nextAttemptAt);
        }

        private static RunResult deadLettered(AsyncTaskRecord task, String errorCode, String errorSummary) {
            return from(task, Outcome.DEAD_LETTERED, errorCode, errorSummary, null);
        }

        private static RunResult leaseLost(AsyncTaskRecord task, String errorSummary) {
            return from(task, Outcome.LEASE_LOST, "LEASE_LOST", errorSummary, null);
        }

        private static RunResult from(
                AsyncTaskRecord task, Outcome outcome, String errorCode, String errorSummary, Instant nextAttemptAt) {
            return new RunResult(
                    outcome, task.publicId(), task.taskType(), task.attempt(), errorCode, errorSummary, nextAttemptAt);
        }
    }
}
