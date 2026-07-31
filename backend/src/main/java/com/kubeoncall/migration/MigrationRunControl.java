package com.kubeoncall.migration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.task.worker.AsyncTaskContext;

/**
 * Cooperative controls shared by every Redis-to-MySQL migration runner.
 *
 * <p>The per-process permit schedule deliberately applies to all migration domains, preventing a
 * burst of parallel task handlers from overloading Redis or MySQL. A handler binds its durable task
 * context for the duration of a run; each source-item boundary then observes cancellation, timeout
 * and worker lease loss before any fact write.
 */
@Component
public class MigrationRunControl {

    private static final MigrationRunControl DISABLED = new MigrationRunControl(null, true);

    private final KubeOnCallProperties properties;
    private final boolean disabled;
    private final AtomicLong nextPermitNanos = new AtomicLong();
    private final ThreadLocal<AsyncTaskContext> taskContext = new ThreadLocal<>();

    @Autowired
    public MigrationRunControl(KubeOnCallProperties properties) {
        this(properties, false);
    }

    private MigrationRunControl(KubeOnCallProperties properties, boolean disabled) {
        this.properties = properties;
        this.disabled = disabled;
    }

    /** Compatibility control used by focused runner tests that do not construct Spring beans. */
    public static MigrationRunControl disabled() {
        return DISABLED;
    }

    public Scope bind(AsyncTaskContext context) {
        AsyncTaskContext previous = taskContext.get();
        taskContext.set(context);
        return () -> {
            if (previous == null) {
                taskContext.remove();
            } else {
                taskContext.set(previous);
            }
        };
    }

    /** Must run immediately before a source item is read or written. */
    public void beforeSourceItem() {
        requireTaskCanProceed();
        if (disabled) {
            return;
        }
        int permitsPerSecond = properties.getDataMigration().getBackfillMaxItemsPerSecond();
        if (permitsPerSecond <= 0) {
            return;
        }
        long spacingNanos = Math.max(1L, TimeUnit.SECONDS.toNanos(1) / permitsPerSecond);
        long now = System.nanoTime();
        long scheduled = nextPermitNanos.getAndUpdate(previous -> {
            long base = Math.max(previous, now);
            long next = base + spacingNanos;
            return next < base ? Long.MAX_VALUE : next;
        });
        long waitNanos = Math.max(0L, Math.max(scheduled, now) - now);
        if (waitNanos > 0L) {
            LockSupport.parkNanos(waitNanos);
            if (Thread.interrupted()) {
                throw new IllegalStateException("Migration backfill worker was interrupted while rate-limited");
            }
        }
        requireTaskCanProceed();
    }

    private void requireTaskCanProceed() {
        AsyncTaskContext context = taskContext.get();
        if (context != null) {
            context.requireValidLease();
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
