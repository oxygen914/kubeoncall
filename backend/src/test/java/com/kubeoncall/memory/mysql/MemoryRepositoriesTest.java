package com.kubeoncall.memory.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class MemoryRepositoriesTest {

    @Test
    void memoryDeletionIsVersionedAndQualityIsBounded() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        MemoryEntryRepository repository = new MemoryEntryRepository(jdbcTemplate, new ObjectMapper(), true);

        boolean deleted = repository.softDelete("mem_1", 6, "superseded", Instant.parse("2026-07-21T02:00:00Z"));

        assertThat(deleted).isTrue();
        assertThat(jdbcTemplate.sql).contains("status = 'DELETED'").contains("version = ? AND deleted_at IS NULL");
        assertThat(jdbcTemplate.args).endsWith("mem_1", 6L);

        MemoryEntryRepository.UpsertMemory invalid = new MemoryEntryRepository.UpsertMemory(
                null,
                "FACT",
                null,
                null,
                null,
                null,
                new BigDecimal("1.01"),
                "0".repeat(64),
                null,
                null,
                Instant.now(),
                null);
        assertThatThrownBy(() -> repository.upsert(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quality");
    }

    @Test
    void extractionProgressUsesLifecycleAndVersionPredicates() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        MemoryExtractionTaskRepository repository =
                new MemoryExtractionTaskRepository(jdbcTemplate, new ObjectMapper(), true);

        boolean updated = repository.updateProgress("mext_1", 2, 5, 3, Instant.parse("2026-07-21T02:00:00Z"));

        assertThat(updated).isTrue();
        assertThat(jdbcTemplate.sql)
                .contains("status = 'RUNNING'")
                .contains("version = version + 1")
                .contains("status IN ('PENDING', 'RUNNING')");
        assertThat(jdbcTemplate.args).endsWith("mext_1", 2L);

        assertThatThrownBy(() -> repository.updateProgress("mext_1", 3, -1, 3, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counters");
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
