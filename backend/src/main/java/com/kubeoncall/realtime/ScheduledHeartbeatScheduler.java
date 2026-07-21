package com.kubeoncall.realtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One daemon scheduler shared by all SSE connections. */
@Component
public final class ScheduledHeartbeatScheduler implements HeartbeatScheduler {

    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private final Duration interval;
    private final ScheduledExecutorService executor;

    @Autowired
    public ScheduledHeartbeatScheduler(@Value("${kubeoncall.realtime.heartbeat-seconds:15}") long heartbeatSeconds) {
        this(
                Duration.ofSeconds(Math.max(1L, heartbeatSeconds)),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "realtime-heartbeat-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    ScheduledHeartbeatScheduler(Duration interval, ScheduledExecutorService executor) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        this.interval = interval;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Registration schedule(Runnable heartbeat) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                heartbeat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
