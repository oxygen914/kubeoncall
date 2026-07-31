package com.kubeoncall.skill.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class SkillStateRepositoryTest {

    @Test
    void enableDisableCommandUsesVersionCompareAndSet() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate(1);
        SkillStateRepository repository = new SkillStateRepository(jdbcTemplate, new ObjectMapper(), true);

        boolean disabled = repository.setEnabled("diagnose-node", 8, false, 3L);

        assertThat(disabled).isTrue();
        assertThat(jdbcTemplate.sql)
                .contains("load_status = CASE")
                .contains("version = version + 1")
                .contains("WHERE skill_id = ? AND version = ? AND enabled <> ?");
        assertThat(jdbcTemplate.args).endsWith("diagnose-node", 8L, false);
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
