package com.kubeoncall.skill.mysql;

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

/** Explicit-SQL Skill state repository with versioned enable/disable commands. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SkillStateRepository {

    private static final String COLUMNS = """
            s.public_id, s.skill_id, s.skill_version, s.checksum, s.source_location,
            s.enabled, s.load_status, s.error_summary, s.metadata_json, s.last_loaded_at,
            s.version, s.created_at, s.updated_at
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public SkillStateRepository(
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

    public SkillStateRecord upsert(UpsertSkillState command) {
        jdbcTemplate.update(
                """
                INSERT INTO koc_skill_state
                  (public_id, skill_id, skill_version, checksum, source_location, enabled,
                   load_status, error_summary, metadata_json, last_loaded_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  skill_version = VALUES(skill_version),
                  checksum = VALUES(checksum),
                  source_location = VALUES(source_location),
                  enabled = VALUES(enabled),
                  load_status = VALUES(load_status),
                  error_summary = VALUES(error_summary),
                  metadata_json = VALUES(metadata_json),
                  last_loaded_at = VALUES(last_loaded_at),
                  updated_by = VALUES(updated_by),
                  version = version + 1
                """,
                publicId(command.publicId()),
                command.skillId(),
                command.skillVersion(),
                checksum(command.checksum()),
                command.sourceLocation(),
                command.enabled(),
                command.enabled() ? defaultValue(command.loadStatus(), "DISCOVERED") : "DISABLED",
                command.errorSummary(),
                json(command.metadata()),
                command.lastLoadedAt(),
                command.updatedBy());
        return find(command.skillId())
                .orElseThrow(
                        () -> new IllegalStateException("Upserted Skill state is not readable: " + command.skillId()));
    }

    public Optional<SkillStateRecord> find(String skillId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM koc_skill_state s WHERE s.skill_id = ?",
                    new SkillStateRowMapper(objectMapper),
                    skillId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public SkillStatePage list(SkillStateQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query.enabled() != null) {
            where.append(" AND s.enabled = ?");
            args.add(query.enabled());
        }
        appendEquals(where, args, "s.load_status", query.loadStatus());
        if (query.text() != null && !query.text().isBlank()) {
            where.append(" AND (s.skill_id LIKE ? OR s.source_location LIKE ?)");
            String pattern = "%" + query.text().trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_skill_state s" + where, Long.class, args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add((page - 1) * size);
        List<SkillStateRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM koc_skill_state s" + where
                        + " ORDER BY s.skill_id ASC, s.id ASC LIMIT ? OFFSET ?",
                new SkillStateRowMapper(objectMapper),
                pagedArgs.toArray());
        return new SkillStatePage(rows, count == null ? 0 : count);
    }

    public boolean setEnabled(String skillId, long expectedVersion, boolean enabled, Long updatedBy) {
        return jdbcTemplate.update("""
                        UPDATE koc_skill_state
                           SET enabled = ?,
                               load_status = CASE
                                 WHEN ? = FALSE THEN 'DISABLED'
                                 WHEN load_status = 'DISABLED' THEN 'DISCOVERED'
                                 ELSE load_status
                               END,
                               error_summary = CASE WHEN ? = TRUE THEN NULL ELSE error_summary END,
                               updated_by = ?,
                               version = version + 1
                         WHERE skill_id = ? AND version = ? AND enabled <> ?
                        """, enabled, enabled, enabled, updatedBy, skillId, expectedVersion, enabled) == 1;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Skill metadata cannot be serialized", ex);
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
                ? "skl_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null
                ? null
                : resultSet.getTimestamp(column).toInstant();
    }

    private static final class SkillStateRowMapper implements RowMapper<SkillStateRecord> {

        private final ObjectMapper objectMapper;

        private SkillStateRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public SkillStateRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            byte[] digest = resultSet.getBytes("checksum");
            return new SkillStateRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("skill_id"),
                    resultSet.getString("skill_version"),
                    digest == null ? null : HexFormat.of().formatHex(digest),
                    resultSet.getString("source_location"),
                    resultSet.getBoolean("enabled"),
                    resultSet.getString("load_status"),
                    resultSet.getString("error_summary"),
                    readMap(objectMapper, resultSet.getString("metadata_json")),
                    instant(resultSet, "last_loaded_at"),
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
            throw new SQLException("Invalid JSON persisted for Skill metadata", ex);
        }
    }

    public record UpsertSkillState(
            String publicId,
            String skillId,
            String skillVersion,
            String checksum,
            String sourceLocation,
            boolean enabled,
            String loadStatus,
            String errorSummary,
            Map<String, Object> metadata,
            Instant lastLoadedAt,
            Long updatedBy) {}

    public record SkillStateQuery(Boolean enabled, String loadStatus, String text, int page, int size) {}

    public record SkillStatePage(List<SkillStateRecord> items, long total) {}
}
