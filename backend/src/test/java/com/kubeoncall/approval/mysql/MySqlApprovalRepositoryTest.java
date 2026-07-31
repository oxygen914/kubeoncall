package com.kubeoncall.approval.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;

class MySqlApprovalRepositoryTest {

    @Test
    void decisionIsCompareAndSetAgainstVersionPendingStatusAndExpiry() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        MySqlApprovalRepository repository = new MySqlApprovalRepository(jdbcTemplate, new ObjectMapper(), true);
        Instant decidedAt = Instant.parse("2026-07-20T02:00:00Z");

        MySqlApprovalRepository.DecisionOutcome outcome =
                repository.decide("apr_1", 4, "approved", 9, decidedAt, "reviewed");

        assertThat(outcome).isEqualTo(MySqlApprovalRepository.DecisionOutcome.DECIDED);
        assertThat(jdbcTemplate.sql)
                .contains("version = version + 1")
                .contains("AND version = ?")
                .contains("AND status = 'PENDING'")
                .contains("AND expires_at > ?");
        assertThat(jdbcTemplate.args).containsSubsequence("APPROVED", "APPROVED", 9L, decidedAt);
        assertThat(jdbcTemplate.args).endsWith("apr_1", 4L, decidedAt);
    }

    @Test
    void missingApprovalIsClassifiedAfterFailedCas() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(0);
        MySqlApprovalRepository repository = new MySqlApprovalRepository(jdbcTemplate, new ObjectMapper(), true);

        MySqlApprovalRepository.DecisionOutcome outcome =
                repository.decide("missing", 1, "REJECTED", 9, Instant.parse("2026-07-20T02:00:00Z"), "unsafe");

        assertThat(outcome).isEqualTo(MySqlApprovalRepository.DecisionOutcome.NOT_FOUND);
    }

    @Test
    void unsupportedDecisionIsRejectedBeforeSql() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        MySqlApprovalRepository repository = new MySqlApprovalRepository(jdbcTemplate, new ObjectMapper(), true);

        assertThatThrownBy(() ->
                        repository.decide("apr_1", 1, "PENDING", 9, Instant.parse("2026-07-20T02:00:00Z"), "invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVED or REJECTED");
        assertThat(jdbcTemplate.sql).isNull();
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

        @Override
        public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) {
            throw new EmptyResultDataAccessException(1);
        }
    }
}
