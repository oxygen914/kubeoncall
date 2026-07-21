package com.kubeoncall.audit.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

class AuditEventRepositoryTest {

    @Test
    void listUsesBoundedExplicitSqlPaginationAndAllFilters() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        AuditEventRepository repository = new AuditEventRepository(jdbcTemplate, new ObjectMapper(), true);
        Instant from = Instant.parse("2026-07-20T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        AuditEventRepository.AuditPage page = repository.list(new AuditEventRepository.AuditQuery(
                "usr_actor", "alarm.acknowledge", "alarm", "alm_1", "SUCCESS", "req_1", from, to, 2, 500));

        assertThat(page.total()).isEqualTo(7);
        assertThat(page.items()).isEmpty();
        assertThat(jdbcTemplate.countSql)
                .contains("LEFT JOIN koc_user")
                .contains("a.actor_type = ?")
                .contains("u.public_id = ?")
                .contains("a.action = ?")
                .contains("a.resource_type = ?")
                .contains("a.resource_public_id = ?")
                .contains("a.result = ?")
                .contains("a.request_id = ?")
                .contains("a.occurred_at >= ?")
                .contains("a.occurred_at <= ?");
        assertThat(jdbcTemplate.listSql)
                .contains("ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?")
                .doesNotContain("a.id,");
        assertThat(jdbcTemplate.listArgs)
                .containsExactly(
                        "usr_actor",
                        "usr_actor",
                        "usr_actor",
                        "alarm.acknowledge",
                        "alarm",
                        "alm_1",
                        "SUCCESS",
                        "req_1",
                        Timestamp.from(from),
                        Timestamp.from(to),
                        100,
                        100);
    }

    @Test
    void missingPublicIdReturnsEmptyWithoutExposingInternalIdLookup() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        jdbcTemplate.missing = true;
        AuditEventRepository repository = new AuditEventRepository(jdbcTemplate, new ObjectMapper(), true);

        Optional<AuditEventRecord> result = repository.find("oaud_missing");

        assertThat(result).isEmpty();
        assertThat(jdbcTemplate.detailSql).contains("WHERE a.public_id = ?").doesNotContain("WHERE a.id = ?");
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {

        private String countSql;
        private String listSql;
        private String detailSql;
        private Object[] listArgs;
        private boolean missing;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            countSql = sql;
            return requiredType.cast(7L);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            listSql = sql;
            listArgs = args;
            return List.of();
        }

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            detailSql = sql;
            if (missing) {
                throw new EmptyResultDataAccessException(1);
            }
            return null;
        }
    }
}
