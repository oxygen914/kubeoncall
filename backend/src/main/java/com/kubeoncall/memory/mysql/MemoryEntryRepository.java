package com.kubeoncall.memory.mysql;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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

/** Explicit-SQL memory fact repository with checksum idempotency and reversible deletion. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MemoryEntryRepository {

    private static final String COLUMNS = """
            m.public_id, m.memory_type, m.status, m.source_session_id,
            m.source_execution_public_id, m.source_alarm_public_id, m.evidence_json,
            m.quality_score, m.content_checksum, m.es_index, m.es_document_id,
            m.extracted_at, m.expires_at, m.version, m.created_at, m.updated_at,
            m.deleted_at, m.delete_reason
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public MemoryEntryRepository(
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

    public MemoryEntryRecord upsert(UpsertMemory command) {
        validateQuality(command.qualityScore());
        byte[] digest = checksum(command.contentChecksum());
        jdbcTemplate.update(
                """
                INSERT INTO koc_memory_entry
                  (public_id, memory_type, status, source_session_id,
                   source_execution_public_id, source_alarm_public_id, evidence_json,
                   quality_score, content_checksum, es_index, es_document_id,
                   extracted_at, expires_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  status = 'ACTIVE',
                  source_session_id = VALUES(source_session_id),
                  source_execution_public_id = VALUES(source_execution_public_id),
                  source_alarm_public_id = VALUES(source_alarm_public_id),
                  evidence_json = VALUES(evidence_json),
                  quality_score = VALUES(quality_score),
                  es_index = VALUES(es_index),
                  es_document_id = VALUES(es_document_id),
                  extracted_at = VALUES(extracted_at),
                  expires_at = VALUES(expires_at),
                  deleted_at = NULL,
                  delete_reason = NULL,
                  version = version + 1
                """,
                publicId(command.publicId()),
                command.memoryType(),
                command.sourceSessionId(),
                command.sourceExecutionPublicId(),
                command.sourceAlarmPublicId(),
                json(command.evidence()),
                command.qualityScore(),
                digest,
                command.esIndex(),
                command.esDocumentId(),
                command.extractedAt(),
                command.expiresAt());
        return findByDedupe(command.memoryType(), digest)
                .orElseThrow(() -> new IllegalStateException("Upserted memory is not readable"));
    }

    public Optional<MemoryEntryRecord> find(String publicId, boolean includeDeleted) {
        String deletedPredicate = includeDeleted ? "" : " AND m.deleted_at IS NULL";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM koc_memory_entry m WHERE m.public_id = ?" + deletedPredicate,
                    new MemoryRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public MemoryPage list(MemoryQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!query.includeDeleted()) {
            where.append(" AND m.deleted_at IS NULL");
        }
        appendEquals(where, args, "m.status", query.status());
        appendEquals(where, args, "m.memory_type", query.memoryType());
        appendEquals(where, args, "m.source_execution_public_id", query.sourceExecutionPublicId());
        appendEquals(where, args, "m.source_alarm_public_id", query.sourceAlarmPublicId());
        if (query.activeAt() != null) {
            where.append(" AND (m.expires_at IS NULL OR m.expires_at > ?)");
            args.add(query.activeAt());
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_memory_entry m" + where, Long.class, args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add((page - 1) * size);
        List<MemoryEntryRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM koc_memory_entry m" + where
                        + " ORDER BY m.updated_at DESC, m.id DESC LIMIT ? OFFSET ?",
                new MemoryRowMapper(objectMapper),
                pagedArgs.toArray());
        return new MemoryPage(rows, count == null ? 0 : count);
    }

    public boolean softDelete(String publicId, long expectedVersion, String reason, Instant deletedAt) {
        return jdbcTemplate.update("""
                        UPDATE koc_memory_entry
                           SET status = 'DELETED', deleted_at = ?, delete_reason = ?,
                               version = version + 1
                         WHERE public_id = ? AND version = ? AND deleted_at IS NULL
                        """, deletedAt, reason, publicId, expectedVersion) == 1;
    }

    public boolean restore(String publicId, long expectedVersion) {
        return jdbcTemplate.update("""
                        UPDATE koc_memory_entry
                           SET status = 'ACTIVE', deleted_at = NULL, delete_reason = NULL,
                               version = version + 1
                         WHERE public_id = ? AND version = ? AND deleted_at IS NOT NULL
                        """, publicId, expectedVersion) == 1;
    }

    private Optional<MemoryEntryRecord> findByDedupe(String memoryType, byte[] digest) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS
                            + " FROM koc_memory_entry m"
                            + " WHERE m.memory_type = ? AND m.content_checksum = ?",
                    new MemoryRowMapper(objectMapper),
                    memoryType,
                    digest));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Memory evidence cannot be serialized", ex);
        }
    }

    private static void validateQuality(BigDecimal quality) {
        if (quality != null && (quality.signum() < 0 || quality.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("Quality score must be between 0 and 1");
        }
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static byte[] checksum(String hex) {
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("Checksum must be a 64-character SHA-256 hex value");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Checksum must be hexadecimal", ex);
        }
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "mem_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null
                ? null
                : resultSet.getTimestamp(column).toInstant();
    }

    private static final class MemoryRowMapper implements RowMapper<MemoryEntryRecord> {

        private final ObjectMapper objectMapper;

        private MemoryRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public MemoryEntryRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            byte[] digest = resultSet.getBytes("content_checksum");
            return new MemoryEntryRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("memory_type"),
                    resultSet.getString("status"),
                    resultSet.getString("source_session_id"),
                    resultSet.getString("source_execution_public_id"),
                    resultSet.getString("source_alarm_public_id"),
                    readMap(objectMapper, resultSet.getString("evidence_json")),
                    resultSet.getBigDecimal("quality_score"),
                    digest == null ? null : HexFormat.of().formatHex(digest),
                    resultSet.getString("es_index"),
                    resultSet.getString("es_document_id"),
                    instant(resultSet, "extracted_at"),
                    instant(resultSet, "expires_at"),
                    resultSet.getLong("version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"),
                    instant(resultSet, "deleted_at"),
                    resultSet.getString("delete_reason"));
        }
    }

    private static Map<String, Object> readMap(ObjectMapper objectMapper, String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid JSON persisted for memory evidence", ex);
        }
    }

    public record UpsertMemory(
            String publicId,
            String memoryType,
            String sourceSessionId,
            String sourceExecutionPublicId,
            String sourceAlarmPublicId,
            Map<String, Object> evidence,
            BigDecimal qualityScore,
            String contentChecksum,
            String esIndex,
            String esDocumentId,
            Instant extractedAt,
            Instant expiresAt) {}

    public record MemoryQuery(
            String status,
            String memoryType,
            String sourceExecutionPublicId,
            String sourceAlarmPublicId,
            boolean includeDeleted,
            Instant activeAt,
            int page,
            int size) {}

    public record MemoryPage(List<MemoryEntryRecord> items, long total) {}
}
