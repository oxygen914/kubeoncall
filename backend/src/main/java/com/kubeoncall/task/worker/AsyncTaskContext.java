package com.kubeoncall.task.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.kubeoncall.task.AsyncTaskRecord;

/**
 * Fenced execution context passed to a task handler.
 *
 * <p>Handlers that commit business state should call {@link #requireValidLease()} immediately
 * before that commit. The worker performs the same check again before completing the durable task.
 */
public final class AsyncTaskContext {

    private final AsyncTaskRecord task;
    private final String ownerToken;
    private final BooleanSupplier leaseValid;
    private final BooleanSupplier cancellationRequested;
    private final Instant deadline;
    private final Clock clock;

    AsyncTaskContext(AsyncTaskRecord task, String ownerToken, BooleanSupplier leaseValid) {
        this(task, ownerToken, leaseValid, () -> false, null, Clock.systemUTC());
    }

    AsyncTaskContext(
            AsyncTaskRecord task,
            String ownerToken,
            BooleanSupplier leaseValid,
            BooleanSupplier cancellationRequested,
            Instant deadline,
            Clock clock) {
        this.task = Objects.requireNonNull(task, "task");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.leaseValid = Objects.requireNonNull(leaseValid, "leaseValid");
        this.cancellationRequested = Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        this.deadline = deadline;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AsyncTaskRecord task() {
        return task;
    }

    public String ownerToken() {
        return ownerToken;
    }

    public long fencingToken() {
        return task.fencingToken();
    }

    public boolean isLeaseValid() {
        return leaseValid.getAsBoolean();
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.getAsBoolean();
    }

    public boolean isTimedOut() {
        return deadline != null && !clock.instant().isBefore(deadline);
    }

    public void requireValidLease() {
        if (isCancellationRequested()) {
            throw new TaskCancelledException();
        }
        if (isTimedOut()) {
            throw new TaskTimedOutException(deadline);
        }
        if (!isLeaseValid()) {
            throw new IllegalStateException("Async task lease is no longer valid");
        }
    }

    /** Signals a durable operator cancellation without treating it as a retryable handler error. */
    public static final class TaskCancelledException extends RuntimeException {

        private TaskCancelledException() {
            super("Async task was cancelled by an operator");
        }
    }

    /** Signals a per-task execution timeout without letting a stale worker retry indefinitely. */
    public static final class TaskTimedOutException extends RuntimeException {

        private TaskTimedOutException(Instant deadline) {
            super("Async task exceeded its execution deadline: " + deadline);
        }
    }
}
