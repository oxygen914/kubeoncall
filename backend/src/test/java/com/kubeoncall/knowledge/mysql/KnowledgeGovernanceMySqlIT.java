package com.kubeoncall.knowledge.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.memory.mysql.MemoryEntryRecord;
import com.kubeoncall.memory.mysql.MemoryEntryRepository;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;

/** Runs V1 through V6 and verifies the WBS-9 repositories against real MySQL 8 semantics. */
@Testcontainers
class KnowledgeGovernanceMySqlIT {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    @Test
    void factsMigrateAndLifecycleOperationsRoundTrip() {
        DataSource dataSource = DataSourceBuilder.create()
                .url(MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false")
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        seedTasks(jdbcTemplate);
        ObjectMapper objectMapper = new ObjectMapper();

        KnowledgeDocumentRepository documentRepository = new KnowledgeDocumentRepository(
                jdbcTemplate, objectMapper, new DataSourceTransactionManager(dataSource), true);
        KnowledgeDocumentRecord document =
                documentRepository.createDocument(new KnowledgeDocumentRepository.CreateDocument(
                        "doc_test",
                        "external-1",
                        "Node guide",
                        "JSONL",
                        "minio://source",
                        "2026-07",
                        Map.of("service", "node"),
                        null));
        KnowledgeDocumentVersionRecord contentVersion =
                documentRepository.createVersion(new KnowledgeDocumentRepository.CreateVersion(
                        "docv_test",
                        document.publicId(),
                        null,
                        SHA_A,
                        "text/markdown",
                        42,
                        "knowledge",
                        "docs/node.md",
                        "PENDING",
                        "qwen-embedding",
                        "v1",
                        1024,
                        null,
                        null,
                        Map.of()));
        assertThat(contentVersion.versionNumber()).isEqualTo(1);
        assertThat(documentRepository.findDocument("doc_test", false))
                .get()
                .extracting(KnowledgeDocumentRecord::currentVersionPublicId)
                .isEqualTo("docv_test");

        KnowledgeImportRepository importRepository = new KnowledgeImportRepository(jdbcTemplate, true);
        KnowledgeImportRecord importRecord = importRepository.create(new KnowledgeImportRepository.CreateImport(
                "imp_test",
                "task_import",
                "JSONL",
                "SKIP",
                false,
                "incoming",
                "batch/data.jsonl",
                SHA_B,
                100,
                "2026-07",
                2L));
        assertThat(importRecord.status()).isEqualTo("PENDING");
        assertThat(importRepository.updateProgress(
                        importRecord.publicId(), importRecord.version(), 2L, 1, 1, 0, 0, Instant.now()))
                .isTrue();
        KnowledgeImportRecord running = importRepository.find("imp_test").orElseThrow();
        assertThat(importRepository.complete(
                        running.publicId(),
                        running.version(),
                        2,
                        1,
                        1,
                        0,
                        "reports",
                        "imp_test-errors.jsonl",
                        Instant.now()))
                .isTrue();
        assertThat(importRepository.find("imp_test"))
                .get()
                .extracting(KnowledgeImportRecord::status)
                .isEqualTo("PARTIAL");

        MemoryEntryRepository memoryRepository = new MemoryEntryRepository(jdbcTemplate, objectMapper, true);
        MemoryEntryRepository.UpsertMemory memoryCommand = new MemoryEntryRepository.UpsertMemory(
                null,
                "SERVICE_FACT",
                "session-1",
                null,
                null,
                Map.of("content", "node-a is owned by SRE"),
                new BigDecimal("0.95"),
                SHA_C,
                null,
                null,
                Instant.now(),
                null);
        MemoryEntryRecord firstMemory = memoryRepository.upsert(memoryCommand);
        MemoryEntryRecord secondMemory = memoryRepository.upsert(memoryCommand);
        assertThat(secondMemory.publicId()).isEqualTo(firstMemory.publicId());
        assertThat(secondMemory.version()).isEqualTo(firstMemory.version() + 1);
        assertThat(memoryRepository.softDelete(firstMemory.publicId(), secondMemory.version(), "stale", Instant.now()))
                .isTrue();
        MemoryEntryRecord deletedMemory =
                memoryRepository.find(firstMemory.publicId(), true).orElseThrow();
        assertThat(memoryRepository.restore(deletedMemory.publicId(), deletedMemory.version()))
                .isTrue();

        MemoryExtractionTaskRepository extractionRepository =
                new MemoryExtractionTaskRepository(jdbcTemplate, objectMapper, true);
        assertThat(extractionRepository
                        .create(new MemoryExtractionTaskRepository.CreateExtraction(
                                "mext_test", "task_memory", "EXECUTION", "exe_test", "extract-1", "qwen-plus", "v1"))
                        .status())
                .isEqualTo("PENDING");

        SkillStateRepository skillRepository = new SkillStateRepository(jdbcTemplate, objectMapper, true);
        SkillStateRecord skill = skillRepository.upsert(new SkillStateRepository.UpsertSkillState(
                "skl_test",
                "diagnose-node",
                "1.0.0",
                SHA_A,
                "skills/diagnose-node",
                true,
                "LOADED",
                null,
                Map.of("tags", "node"),
                Instant.now(),
                null));
        assertThat(skill.enabled()).isTrue();
        assertThat(skillRepository.setEnabled(skill.skillId(), skill.version(), false, null))
                .isTrue();
        assertThat(skillRepository.find(skill.skillId())).get().satisfies(state -> {
            assertThat(state.enabled()).isFalse();
            assertThat(state.loadStatus()).isEqualTo("DISABLED");
        });
    }

    private void migrate() {
        org.flywaydb.core.Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private void seedTasks(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                INSERT INTO koc_async_task
                  (public_id, task_type, resource_type, dedupe_key, status, request_id)
                VALUES
                  ('task_import', 'KNOWLEDGE_IMPORT', 'KNOWLEDGE', 'import-1', 'PENDING', 'req-import'),
                  ('task_memory', 'MEMORY_EXTRACTION', 'MEMORY', 'memory-1', 'PENDING', 'req-memory')
                """);
    }
}
