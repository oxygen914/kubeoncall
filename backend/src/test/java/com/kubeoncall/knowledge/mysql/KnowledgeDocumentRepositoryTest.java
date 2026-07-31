package com.kubeoncall.knowledge.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;

class KnowledgeDocumentRepositoryTest {

    @Test
    void softDeleteAndRestoreUseVersionedLifecyclePredicates() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        KnowledgeDocumentRepository repository = new KnowledgeDocumentRepository(
                jdbcTemplate, new ObjectMapper(), mock(PlatformTransactionManager.class), true);

        boolean deleted = repository.softDelete("doc_1", 4, 9L, "obsolete", Instant.parse("2026-07-21T01:00:00Z"));

        assertThat(deleted).isTrue();
        assertThat(jdbcTemplate.sql)
                .contains("status = 'DELETED'")
                .contains("version = version + 1")
                .contains("version = ? AND deleted_at IS NULL");
        assertThat(jdbcTemplate.args).endsWith("doc_1", 4L);

        boolean restored = repository.restore("doc_1", 5);

        assertThat(restored).isTrue();
        assertThat(jdbcTemplate.sql).contains("status = 'ACTIVE'").contains("deleted_at IS NOT NULL");
        assertThat(jdbcTemplate.args).containsExactly("doc_1", 5L);
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
