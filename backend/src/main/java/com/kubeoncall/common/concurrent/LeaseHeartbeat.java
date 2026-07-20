package com.kubeoncall.common.concurrent;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Renews an ownership lease while a long-running operation is still active. */
public final class LeaseHeartbeat implements AutoCloseable {

    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final ScheduledExecutorService executor;
    private final ScheduledFuture<?> renewal;

    private LeaseHeartbeat(Duration leaseTtl, BooleanSupplier renewLease, String threadName) {
        Duration safeTtl =
                leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero() ? Duration.ofMinutes(5) : leaseTtl;
        long intervalMillis = Math.max(100L, safeTtl.toMillis() / 3L);
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
        renewal = executor.scheduleAtFixedRate(
                () -> valid.compareAndSet(true, renewSafely(renewLease)),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    public static LeaseHeartbeat start(Duration leaseTtl, BooleanSupplier renewLease, String threadName) {
        if (renewLease == null) {
            throw new IllegalArgumentException("Lease renewal callback is required");
        }
        String safeThreadName = threadName == null || threadName.isBlank() ? "lease-heartbeat" : threadName.trim();
        return new LeaseHeartbeat(leaseTtl, renewLease, safeThreadName);
    }

    public boolean isValid() {
        return valid.get();
    }

    public void requireValid(String message) {
        if (!isValid()) {
            throw new IllegalStateException(message);
        }
    }

    @Override
    public void close() {
        renewal.cancel(true);
        executor.shutdownNow();
    }

    private boolean renewSafely(BooleanSupplier renewLease) {
        try {
            return renewLease.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
