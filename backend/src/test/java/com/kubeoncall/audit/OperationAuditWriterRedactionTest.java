package com.kubeoncall.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class OperationAuditWriterRedactionTest {

    @Test
    void redactsAuditCopiesButPreservesBusinessIdentifiers() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        OperationAuditWriter writer = new OperationAuditWriter(provider(jdbcTemplate), new ObjectMapper());

        writer.write(OperationAuditWriter.builder()
                .actor("USER", 7L, "Operator")
                .action("skill.enabled.update")
                .resource("skill", "skl_1")
                .reason("password=hunter2")
                .before(Map.of(
                        "skillId", "diagnose-node",
                        "apiKey", "sk-abcdefghijklmnopqrstuvwxyz"))
                .after(Map.of("enabled", false, "note", "Authorization: Bearer abcdefghijklmnopqrstuvwxyz"))
                .requestId("req_1")
                .build());

        assertThat(jdbcTemplate.args[4]).isEqualTo("skill.enabled.update");
        assertThat(jdbcTemplate.args[6]).isEqualTo("skl_1");
        assertThat(jdbcTemplate.args[8]).isEqualTo("password=[REDACTED]");
        assertThat(String.valueOf(jdbcTemplate.args[9]))
                .contains("\"skillId\":\"diagnose-node\"")
                .contains("\"apiKey\":\"[REDACTED]\"")
                .doesNotContain("sk-abcdefghijklmnopqrstuvwxyz");
        assertThat(String.valueOf(jdbcTemplate.args[10]))
                .contains("Bearer [REDACTED]")
                .doesNotContain("abcdefghijklmnopqrstuvwxyz");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate jdbcTemplate) {
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        return provider;
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {

        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.args = args;
            return 1;
        }
    }
}
