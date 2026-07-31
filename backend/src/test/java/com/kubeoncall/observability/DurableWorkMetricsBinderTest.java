package com.kubeoncall.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DurableWorkMetricsBinderTest {

    @Test
    void shouldExposeOutboxAndTaskBacklogsWithoutCachingDatabaseState() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("koc_outbox_event") && sql.contains("COUNT")) {
                return sql.contains("PENDING") ? 3L : 1L;
            }
            if (sql.contains("koc_async_task") && sql.contains("COUNT")) {
                return sql.contains("DEAD_LETTER") ? 2L : 4L;
            }
            if (sql.contains("koc_sandbox_run") && sql.contains("COUNT")) {
                return sql.contains("PENDING") ? 5L : 1L;
            }
            return 12.5;
        });

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DurableWorkMetricsBinder(jdbcTemplate).bindTo(registry);

        assertThat(gauge(registry, "kubeoncall_outbox_events", "status", "PENDING")
                        .value())
                .isEqualTo(3.0);
        assertThat(gauge(registry, "kubeoncall_async_tasks", "status", "DEAD_LETTER")
                        .value())
                .isEqualTo(2.0);
        assertThat(registry.get("kubeoncall_outbox_oldest_pending_age_seconds")
                        .gauge()
                        .value())
                .isEqualTo(12.5);
        assertThat(registry.get("kubeoncall_async_task_oldest_due_age_seconds")
                        .gauge()
                        .value())
                .isEqualTo(12.5);
        assertThat(gauge(registry, "kubeoncall_sandbox_runs", "status", "PENDING")
                        .value())
                .isEqualTo(5.0);
        assertThat(gauge(registry, "kubeoncall_sandbox_cleanup", "status", "FAILED")
                        .value())
                .isEqualTo(1.0);
        assertThat(registry.get("kubeoncall_sandbox_oldest_active_age_seconds")
                        .gauge()
                        .value())
                .isEqualTo(12.5);
    }

    @Test
    void shouldReturnNanInsteadOfBreakingMetricScrapesWhenDatabaseIsUnavailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new DurableWorkMetricsBinder(jdbcTemplate).bindTo(registry);

        assertThat(gauge(registry, "kubeoncall_outbox_events", "status", "PENDING")
                        .value())
                .isNaN();
        assertThat(registry.get("kubeoncall_async_task_oldest_due_age_seconds")
                        .gauge()
                        .value())
                .isNaN();
    }

    private static Gauge gauge(SimpleMeterRegistry registry, String name, String tagName, String tagValue) {
        return registry.get(name).tag(tagName, tagValue).gauge();
    }
}
