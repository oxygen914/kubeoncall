package com.kubeoncall.memory.mysql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit-SQL lifecycle repository for durable memory extraction tasks. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MemoryExtractionTaskRepository {

    private static final String COLUMNS = """
            e.public_id, t.public_id AS task_public_id, e.source_type, e.source_public_id,
            e.dedupe_key, e.status, e.extractor_model, e.extractor_version,
            e.evidence_count, e.memory_count, e.quality_summary_json, e.error_code,
            e.error_summary, e.started_at, e.finished_at, e.version, e.created_at, e.updated_at
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public MemoryExtractionTaskRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    public MemoryExtractionTaskRecord create(CreateExtraction command) {
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM koc_async_task WHERE public_id = ?", Long.class, command.taskPublicId());
        if (taskId == null) {
            throw new IllegalArgumentException("Unknown async task: " + command.taskPublicId());
        }
        String publicId = publicId(command.publicId());
        jdbcTemplate.update(
                """
                INSERT INTO koc_memory_extraction_task
                  (public_id, task_id, source_type, source_public_id, dedupe_key, status,
                   extractor_model, extractor_version)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                publicId,
                taskId,
                command.sourceType(),
                command.sourcePublicId(),
                command.dedupeKey(),
                command.extractorModel(),
                command.extractorVersion());
        return find(publicId)
                .orElseThrow(() -> new IllegalStateException("Created memory extraction is not readable: " + publicId));
    }

    public Optional<MemoryExtractionTaskRecord> find(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS
                            + " FROM koc_memory_extraction_task e"
                            + " JOIN koc_async_task t ON t.id = e.task_id"
                            + " WHERE e.public_id = ?",
                    new ExtractionRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public ExtractionPage list(ExtractionQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendEquals(where, args, "e.status", query.status());
        appendEquals(where, args, "e.source_type", query.sourceType());
        appendEquals(where, args, "e.source_public_id", query.sourcePublicId());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_memory_extraction_task e" + where, Long.class, args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add((page - 1) * size);
        List<MemoryExtractionTaskRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM koc_memory_extraction_task e"
                        + " JOIN koc_async_task t ON t.id = e.task_id"
                        + where
                        + " ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
                new ExtractionRowMapper(objectMapper),
                pagedArgs.toArray());
        return new ExtractionPage(rows, count == null ? 0 : count);
    }

    public boolean updateProgress(
            String publicId, long expectedVersion, int evidenceCount, int memoryCount, Instant startedAt) {
        validateCounts(evidenceCount, memoryCount);
        return jdbcTemplate.update("""
                        UPDATE koc_memory_extraction_task
                           SET status = 'RUNNING', evidence_count = ?, memory_count = ?,
                               started_at = COALESCE(started_at, ?), version = version + 1
                         WHERE public_id = ? AND version = ?
                           AND status IN ('PENDING', 'RUNNING')
                        """, evidenceCount, memoryCount, startedAt, publicId, expectedVersion) == 1;
    }

    public boolean complete(
            String publicId,
            long expectedVersion,
            int evidenceCount,
            int memoryCount,
            Map<String, Object> qualitySummary,
            Instant finishedAt) {
        validateCounts(evidenceCount, memoryCount);
        return jdbcTemplate.update(
                        """
                        UPDATE koc_memory_extraction_task
                           SET status = 'SUCCEEDED', evidence_count = ?, memory_count = ?,
                               quality_summary_json = ?, finished_at = ?, version = version + 1
                         WHERE public_id = ? AND version = ?
                           AND status IN ('PENDING', 'RUNNING')
                        """, evidenceCount, memoryCount, json(qualitySummary), finishedAt, publicId, expectedVersion)
                == 1;
    }

    public boolean fail(
            String publicId, long expectedVersion, String errorCode, String errorSummary, Instant finishedAt) {
        return jdbcTemplate.update("""
                        UPDATE koc_memory_extraction_task
                           SET status = 'FAILED', error_code = ?, error_summary = ?,
                               finished_at = ?, version = version + 1
                         WHERE public_id = ? AND version = ?
                           AND status IN ('PENDING', 'RUNNING')
                        """, errorCode, errorSummary, finishedAt, publicId, expectedVersion) == 1;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Quality summary cannot be serialized", ex);
        }
    }

    private static void validateCounts(int evidenceCount, int memoryCount) {
        if (evidenceCount < 0 || memoryCount < 0) {
            throw new IllegalArgumentException("Extraction counters are inconsistent");
        }
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "mext_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null
                ? null
                : resultSet.getTimestamp(column).toInstant();
    }

    private static final class ExtractionRowMapper implements RowMapper<MemoryExtractionTaskRecord> {

        private final ObjectMapper objectMapper;

        private ExtractionRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public MemoryExtractionTaskRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return new MemoryExtractionTaskRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("task_public_id"),
                    resultSet.getString("source_type"),
                    resultSet.getString("source_public_id"),
                    resultSet.getString("dedupe_key"),
                    resultSet.getString("status"),
                    resultSet.getString("extractor_model"),
                    resultSet.getString("extractor_version"),
                    resultSet.getInt("evidence_count"),
                    resultSet.getInt("memory_count"),
                    readMap(objectMapper, resultSet.getString("quality_summary_json")),
                    resultSet.getString("error_code"),
                    resultSet.getString("error_summary"),
                    instant(resultSet, "started_at"),
                    instant(resultSet, "finished_at"),
                    resultSet.getLong("version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"));
        }
    }

    private static Map<String, Object> readMap(ObjectMapper objectMapper, String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid JSON persisted for extraction quality summary", ex);
        }
    }

    public record CreateExtraction(
            String publicId,
            String taskPublicId,
            String sourceType,
            String sourcePublicId,
            String dedupeKey,
            String extractorModel,
            String extractorVersion) {}

    public record ExtractionQuery(String status, String sourceType, String sourcePublicId, int page, int size) {}

    public record ExtractionPage(List<MemoryExtractionTaskRecord> items, long total) {}
}
