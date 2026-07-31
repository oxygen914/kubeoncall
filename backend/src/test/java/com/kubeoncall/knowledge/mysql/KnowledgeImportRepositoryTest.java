package com.kubeoncall.knowledge.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeImportRepositoryTest {

    @Test
    void progressUsesVersionAndRejectsInconsistentCounters() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        KnowledgeImportRepository repository = new KnowledgeImportRepository(jdbcTemplate, true);

        boolean updated = repository.updateProgress("imp_1", 2, 10L, 6, 4, 1, 1, Instant.parse("2026-07-21T01:00:00Z"));

        assertThat(updated).isTrue();
        assertThat(jdbcTemplate.sql)
                .contains("status = 'RUNNING'")
                .contains("version = version + 1")
                .contains("status IN ('PENDING', 'RUNNING')");
        assertThat(jdbcTemplate.args).endsWith("imp_1", 2L);

        assertThatThrownBy(() -> repository.updateProgress("imp_1", 3, 10L, 7, 4, 1, 1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counters");
    }

    @Test
    void completionWithFailedRowsIsMarkedPartialAndKeepsErrorReportReference() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        KnowledgeImportRepository repository = new KnowledgeImportRepository(jdbcTemplate, true);

        boolean completed = repository.complete(
                "imp_1",
                3,
                10,
                8,
                1,
                1,
                "reports",
                "imports/imp_1-errors.jsonl",
                Instant.parse("2026-07-21T01:05:00Z"));

        assertThat(completed).isTrue();
        assertThat(jdbcTemplate.args)
                .startsWith("PARTIAL", 10L, 8L, 1L, 1L, "reports", "imports/imp_1-errors.jsonl")
                .endsWith("imp_1", 3L);
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
