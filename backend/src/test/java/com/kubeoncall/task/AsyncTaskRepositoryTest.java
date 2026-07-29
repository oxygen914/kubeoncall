package com.kubeoncall.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

class AsyncTaskRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-20T03:00:00Z");

    @Test
    void claimUsesSkipLockedAndAdvancesAttemptAndFence() {
        AsyncTaskRecord claimed = record("tsk_1", "owner-new", 6);
        ClaimJdbcTemplate jdbcTemplate = new ClaimJdbcTemplate(claimed);
        AsyncTaskRepository repository = new AsyncTaskRepository(jdbcTemplate, new ObjectMapper(), true);

        Optional<AsyncTaskRecord> result =
                repository.claimNext("owner-new", NOW, Duration.ofSeconds(30), Set.of("WORKFLOW_RESUME"));

        assertThat(result).contains(claimed);
        assertThat(jdbcTemplate.claimSql)
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("status = 'RUNNING'")
                .contains("lease_until <=");
        assertThat(jdbcTemplate.updateSql)
                .contains("fencing_token = fencing_token + 1")
                .contains("attempt = attempt + 1");
    }

    @Test
    void everyCompletionWriteRequiresOwnerFenceAndLiveLease() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        AsyncTaskRepository repository = new AsyncTaskRepository(jdbcTemplate, new ObjectMapper(), true);

        assertThat(repository.complete("tsk_1", "owner-1", 7, Map.of("ok", true), NOW))
                .isTrue();
        assertOwnershipPredicate(jdbcTemplate.sql);
        assertThat(jdbcTemplate.sql).contains("stage = 'COMPLETED'");

        assertThat(repository.fail("tsk_1", "owner-1", 7, "FAILED", "terminal", NOW))
                .isTrue();
        assertOwnershipPredicate(jdbcTemplate.sql);
        assertThat(jdbcTemplate.sql).contains("stage = 'FAILED'");

        assertThat(repository.retry("tsk_1", "owner-1", 7, "TIMEOUT", "retry", NOW.plusSeconds(10), NOW))
                .isTrue();
        assertOwnershipPredicate(jdbcTemplate.sql);
        assertThat(jdbcTemplate.sql).contains("stage = 'RETRY_SCHEDULED'").contains("attempt < max_attempts");

        assertThat(repository.deadLetter("tsk_1", "owner-1", 7, "EXHAUSTED", "dead", NOW))
                .isTrue();
        assertOwnershipPredicate(jdbcTemplate.sql);
        assertThat(jdbcTemplate.sql).contains("stage = 'DEAD_LETTER'");
    }

    @Test
    void staleOwnerUpdateReturningZeroIsNotReportedAsSuccess() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(0);
        AsyncTaskRepository repository = new AsyncTaskRepository(jdbcTemplate, new ObjectMapper(), true);

        assertThat(repository.heartbeat("tsk_1", "old-owner", 4, NOW, Duration.ofSeconds(30)))
                .isFalse();
        assertOwnershipPredicate(jdbcTemplate.sql);
    }

    @Test
    void progressUpdateUsesTheSameOwnershipFence() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        AsyncTaskRepository repository = new AsyncTaskRepository(jdbcTemplate, new ObjectMapper(), true);

        assertThat(repository.updateProgress("tsk_1", "owner-1", 7, "PARSING", 45, NOW))
                .isTrue();
        assertThat(jdbcTemplate.sql)
                .contains("SET stage = ?")
                .contains("progress = ?")
                .contains("version = version + 1");
        assertOwnershipPredicate(jdbcTemplate.sql);
        assertThatThrownBy(() -> repository.updateProgress("tsk_1", "owner-1", 7, "DONE", 100, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 99");
    }

    @Test
    void invalidOwnershipAndRetryScheduleAreRejectedBeforeSql() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        AsyncTaskRepository repository = new AsyncTaskRepository(jdbcTemplate, new ObjectMapper(), true);

        assertThatThrownBy(() -> repository.complete("tsk_1", "owner", 0, Map.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fencingToken");
        assertThatThrownBy(() -> repository.retry("tsk_1", "owner", 1, "E", "bad", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextAttemptAt");
        assertThat(jdbcTemplate.sql).isNull();
    }

    private static void assertOwnershipPredicate(String sql) {
        assertThat(sql)
                .contains("status = 'RUNNING'")
                .contains("owner_token = ?")
                .contains("fencing_token = ?")
                .contains("lease_until > ?");
    }

    private static AsyncTaskRecord record(String publicId, String ownerToken, long fencingToken) {
        return new AsyncTaskRecord(
                42,
                publicId,
                "WORKFLOW_RESUME",
                "WORKFLOW_EXECUTION",
                "exe_1",
                "resume-exe-1",
                "RUNNING",
                "resume",
                0,
                Map.of(),
                Map.of(),
                null,
                null,
                ownerToken,
                NOW.plusSeconds(30),
                fencingToken,
                2,
                5,
                NOW,
                NOW,
                null,
                "req_1",
                "trace_1",
                2,
                NOW,
                NOW);
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {

        private final int updateCount;
        protected String sql;

        private CapturingJdbcTemplate(int updateCount) {
            this.updateCount = updateCount;
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            return updateCount;
        }
    }

    private static final class ClaimJdbcTemplate extends CapturingJdbcTemplate {

        private final AsyncTaskRecord task;
        private String claimSql;
        private String updateSql;

        private ClaimJdbcTemplate(AsyncTaskRecord task) {
            super(1);
            this.task = task;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.claimSql = sql;
            return (List<T>) List.of(42L);
        }

        @Override
        public int update(String sql, Object... args) {
            this.updateSql = sql;
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            return (T) task;
        }
    }
}
