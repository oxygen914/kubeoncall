package com.kubeoncall.agent.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class OperationClosureMigrationTest {

    @Test
    void migrationDefinesDurableClosureAndEscalationFacts() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V20__operation_closure_facts.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE koc_operation_closure_fact")
                .contains("UNIQUE KEY uk_operation_closure_operation_id")
                .contains("execution_public_id VARCHAR(40)")
                .contains("details_json JSON")
                .contains("CREATE TABLE koc_operation_escalation_fact")
                .contains("UNIQUE KEY uk_operation_escalation_operation_id")
                .contains("idx_operation_escalation_status");
    }
}
