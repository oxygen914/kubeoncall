package com.kubeoncall.task.worker;

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

    AsyncTaskContext(AsyncTaskRecord task, String ownerToken, BooleanSupplier leaseValid) {
        this.task = Objects.requireNonNull(task, "task");
        this.ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
        this.leaseValid = Objects.requireNonNull(leaseValid, "leaseValid");
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

    public void requireValidLease() {
        if (!isLeaseValid()) {
            throw new IllegalStateException("Async task lease is no longer valid");
        }
    }
}
