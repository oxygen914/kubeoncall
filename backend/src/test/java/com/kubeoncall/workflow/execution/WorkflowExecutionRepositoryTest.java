package com.kubeoncall.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkflowExecutionRepositoryTest {

    @Test
    void executionStatusUpdateUsesOptimisticVersion() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        WorkflowExecutionRepository repository = new WorkflowExecutionRepository(jdbcTemplate, true);

        boolean updated = repository.updateStatus(
                "exe_1",
                7,
                "SUCCEEDED",
                "done",
                null,
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:01:00Z"));

        assertThat(updated).isTrue();
        assertThat(jdbcTemplate.sql).contains("WHERE public_id = ? AND version = ?");
        assertThat(jdbcTemplate.args).endsWith("exe_1", 7L);
    }

    @Test
    void nodeUpdateUsesVersionAndComputesDurationInDatabase() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(0);
        WorkflowExecutionRepository repository = new WorkflowExecutionRepository(jdbcTemplate, true);

        boolean updated = repository.updateNode(
                "node_1",
                3,
                "FAILED",
                null,
                "TOOL_TIMEOUT",
                "tool did not respond",
                Instant.parse("2026-07-20T01:01:00Z"));

        assertThat(updated).isFalse();
        assertThat(jdbcTemplate.sql)
                .contains("TIMESTAMPDIFF(MICROSECOND, started_at")
                .contains("WHERE public_id = ? AND version = ?");
        assertThat(jdbcTemplate.args).endsWith("node_1", 3L);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {

        private final int updateCount;
        private String sql;
        private Object[] args;

        private CapturingJdbcTemplate(int updateCount) {
            this.updateCount = updateCount;
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return updateCount;
        }
    }
}
