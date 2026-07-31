package com.kubeoncall.observability;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Pull-based backlog gauges for the durable outbox and asynchronous task tables.
 *
 * <p>Every query is isolated: a missing table or transient database failure returns {@code NaN}
 * instead of failing the Prometheus scrape or a business request. The first failure for a gauge is
 * logged once and the warning is re-armed after a successful query.
 */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class DurableWorkMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(DurableWorkMetricsBinder.class);
    private static final List<String> OUTBOX_STATUSES = List.of("PENDING", "PROCESSING", "DEAD_LETTER");
    private static final List<String> TASK_STATUSES = List.of("PENDING", "RETRY", "RUNNING", "DEAD_LETTER");
    private static final List<String> SANDBOX_RUN_STATUSES =
            List.of("PENDING", "DISPATCHING", "RUNNING", "COLLECTING", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED");
    private static final List<String> SANDBOX_CLEANUP_STATUSES =
            List.of("NOT_REQUIRED", "PENDING", "RUNNING", "SUCCEEDED", "FAILED");

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, AtomicBoolean> failedQueries = new ConcurrentHashMap<>();

    public DurableWorkMetricsBinder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String status : OUTBOX_STATUSES) {
            Gauge.builder("kubeoncall_outbox_events", this, ignored -> outboxCount(status))
                    .description("Current durable outbox events by lifecycle status")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder("kubeoncall_outbox_oldest_pending_age_seconds", this, ignored -> outboxOldestPendingAge())
                .description("Age in seconds of the oldest pending outbox event")
                .register(registry);

        for (String status : TASK_STATUSES) {
            Gauge.builder("kubeoncall_async_tasks", this, ignored -> taskCount(status))
                    .description("Current durable asynchronous tasks by lifecycle status")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder("kubeoncall_async_task_oldest_due_age_seconds", this, ignored -> taskOldestDueAge())
                .description("Age in seconds of the oldest due pending or retry task")
                .register(registry);

        for (String status : SANDBOX_RUN_STATUSES) {
            Gauge.builder("kubeoncall_sandbox_runs", this, ignored -> sandboxRunCount(status))
                    .description("Current Sandbox Runs by lifecycle status")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder("kubeoncall_sandbox_oldest_active_age_seconds", this, ignored -> sandboxOldestActiveAge())
                .description("Age in seconds of the oldest active Sandbox Run")
                .register(registry);

        for (String status : SANDBOX_CLEANUP_STATUSES) {
            Gauge.builder("kubeoncall_sandbox_cleanup", this, ignored -> sandboxCleanupCount(status))
                    .description("Current Sandbox cleanup records by lifecycle status")
                    .tag("status", status)
                    .register(registry);
        }
        Gauge.builder(
                        "kubeoncall_sandbox_oldest_cleanup_pending_age_seconds",
                        this,
                        ignored -> sandboxOldestCleanupPendingAge())
                .description("Age in seconds of the oldest Sandbox cleanup waiting to finish")
                .register(registry);
    }

    double outboxCount(String status) {
        return query("outbox.count." + status, "SELECT COUNT(*) FROM koc_outbox_event WHERE status = '" + status + "'");
    }

    double outboxOldestPendingAge() {
        return query("outbox.oldest.pending", """
                SELECT COALESCE(
                    GREATEST(
                        TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6))
                            / 1000000.0,
                        0
                    ),
                    0
                )
                  FROM koc_outbox_event
                 WHERE status = 'PENDING'
                """);
    }

    double taskCount(String status) {
        return query("task.count." + status, "SELECT COUNT(*) FROM koc_async_task WHERE status = '" + status + "'");
    }

    double taskOldestDueAge() {
        return query("task.oldest.due", """
                SELECT COALESCE(
                    GREATEST(
                        MAX(TIMESTAMPDIFF(MICROSECOND, next_attempt_at, UTC_TIMESTAMP(6)))
                            / 1000000.0,
                        0
                    ),
                    0
                )
                  FROM koc_async_task
                 WHERE status IN ('PENDING', 'RETRY')
                   AND next_attempt_at <= UTC_TIMESTAMP(6)
                """);
    }

    double sandboxRunCount(String status) {
        return query(
                "sandbox.run.count." + status,
                "SELECT COUNT(*) FROM koc_sandbox_run WHERE run_status = '" + status + "'");
    }

    double sandboxOldestActiveAge() {
        return query("sandbox.run.oldest.active", """
                SELECT COALESCE(
                    GREATEST(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(6)) / 1000000.0, 0),
                    0
                )
                  FROM koc_sandbox_run
                 WHERE run_status IN ('PENDING', 'DISPATCHING', 'RUNNING', 'COLLECTING')
                """);
    }

    double sandboxCleanupCount(String status) {
        return query(
                "sandbox.cleanup.count." + status,
                "SELECT COUNT(*) FROM koc_sandbox_run WHERE cleanup_status = '" + status + "'");
    }

    double sandboxOldestCleanupPendingAge() {
        return query("sandbox.cleanup.oldest.pending", """
                SELECT COALESCE(
                    GREATEST(TIMESTAMPDIFF(MICROSECOND, MIN(updated_at), UTC_TIMESTAMP(6)) / 1000000.0, 0),
                    0
                )
                  FROM koc_sandbox_run
                 WHERE cleanup_status IN ('PENDING', 'RUNNING', 'FAILED')
                """);
    }

    private double query(String metricKey, String sql) {
        try {
            Number value = jdbcTemplate.queryForObject(sql, Number.class);
            failedQueries.remove(metricKey);
            return value == null ? 0.0 : Math.max(0.0, value.doubleValue());
        } catch (RuntimeException ex) {
            AtomicBoolean warned = failedQueries.computeIfAbsent(metricKey, ignored -> new AtomicBoolean());
            if (warned.compareAndSet(false, true)) {
                log.warn(
                        "Durable-work gauge query failed: metricKey={}, errorType={}",
                        metricKey,
                        ex.getClass().getSimpleName());
            } else {
                log.debug(
                        "Durable-work gauge query remains unavailable: metricKey={}, errorType={}",
                        metricKey,
                        ex.getClass().getSimpleName());
            }
            return Double.NaN;
        }
    }
}
