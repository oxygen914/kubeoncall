package com.kubeoncall.workflow.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WorkflowStorageMigrationTest {

    @Test
    void migrationDefinesAllWbs7FactsAndSafetyConstraints() throws IOException {
        String sql;
        try (InputStream input =
                getClass().getResourceAsStream("/db/migration/V5__workflow_execution_approval_tasks.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE koc_workflow_execution")
                .contains("CREATE TABLE koc_workflow_node_execution")
                .contains("CREATE TABLE koc_approval_request")
                .contains("CREATE TABLE koc_async_task")
                .contains("UNIQUE KEY uk_workflow_execution_dedupe")
                .contains("UNIQUE KEY uk_workflow_node_attempt")
                .contains("UNIQUE KEY uk_approval_request_dedupe")
                .contains("UNIQUE KEY uk_async_task_dedupe")
                .contains("owner_token VARCHAR(128)")
                .contains("lease_until DATETIME(6)")
                .contains("fencing_token BIGINT UNSIGNED")
                .contains("CONSTRAINT fk_alarm_latest_execution")
                .contains("CONSTRAINT chk_workflow_execution_status")
                .contains("CONSTRAINT chk_approval_status")
                .contains("CONSTRAINT chk_async_task_status");
    }
}
