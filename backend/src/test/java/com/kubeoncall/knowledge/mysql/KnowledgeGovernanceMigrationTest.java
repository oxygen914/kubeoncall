package com.kubeoncall.knowledge.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class KnowledgeGovernanceMigrationTest {

    @Test
    void migrationDefinesWbs9FactsAndSafetyConstraints() throws IOException {
        String sql;
        try (InputStream input =
                getClass().getResourceAsStream("/db/migration/V6__knowledge_memory_skill_governance.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("CREATE TABLE koc_knowledge_document")
                .contains("CREATE TABLE koc_knowledge_document_version")
                .contains("CREATE TABLE koc_knowledge_import")
                .contains("CREATE TABLE koc_memory_entry")
                .contains("CREATE TABLE koc_memory_extraction_task")
                .contains("CREATE TABLE koc_skill_state")
                .contains("UNIQUE KEY uk_knowledge_version_checksum")
                .contains("UNIQUE KEY uk_knowledge_import_source")
                .contains("UNIQUE KEY uk_memory_entry_dedupe")
                .contains("UNIQUE KEY uk_memory_extraction_dedupe")
                .contains("UNIQUE KEY uk_skill_state_skill_id")
                .contains("deleted_at DATETIME(6)")
                .contains("version BIGINT UNSIGNED")
                .contains("CONSTRAINT fk_knowledge_import_task")
                .contains("CONSTRAINT fk_memory_extraction_task")
                .contains("CONSTRAINT chk_knowledge_import_counts");
    }
}
