package com.kubeoncall.evidence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** MySQL metadata repository for Evidence v2. Large bodies remain object-store references. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class EvidenceRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvidenceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsert(String executionPublicId, EvidenceItem item) {
        int updated = jdbcTemplate.update(
                """
                INSERT INTO koc_evidence_item
                  (public_id, execution_id, evidence_type, source, cluster_name, namespace_name,
                   resource_kind, resource_name, resource_uid, observed_at, window_start, window_end,
                   summary, snippet, locator_json, freshness_seconds, redacted, truncated,
                   content_hash, collection_status, error_type, artifact_reference, metadata_json)
                SELECT ?, e.id, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?,
                       ?, ?, CAST(? AS JSON)
                  FROM koc_workflow_execution e
                 WHERE e.public_id = ?
                ON DUPLICATE KEY UPDATE
                  observed_at = VALUES(observed_at),
                  freshness_seconds = VALUES(freshness_seconds),
                  collection_status = VALUES(collection_status),
                  error_type = VALUES(error_type),
                  metadata_json = VALUES(metadata_json)
                """,
                item.evidenceId(),
                item.type().name(),
                item.source(),
                nullIfBlank(item.cluster()),
                nullIfBlank(item.namespace()),
                nullIfBlank(item.resource().kind()),
                nullIfBlank(item.resource().name()),
                nullIfBlank(item.resource().uid()),
                item.observedAt(),
                item.window() == null ? null : item.window().start(),
                item.window() == null ? null : item.window().end(),
                item.summary(),
                nullIfBlank(item.snippet()),
                json(item.locator()),
                item.freshnessSeconds(),
                item.redacted(),
                item.truncated(),
                item.contentHash(),
                item.collectionStatus().name(),
                nullIfBlank(item.errorType()),
                nullIfBlank(item.artifactReference()),
                json(item.metadata()),
                executionPublicId);
        if (updated == 0 && !executionExists(executionPublicId)) {
            throw new IllegalArgumentException("Unknown workflow execution: " + executionPublicId);
        }
    }

    public List<EvidenceItem> list(String executionPublicId) {
        return jdbcTemplate.query("""
                SELECT i.public_id, e.public_id AS execution_public_id, i.evidence_type, i.source,
                       i.cluster_name, i.namespace_name, i.resource_kind, i.resource_name,
                       i.resource_uid, i.observed_at, i.window_start, i.window_end, i.summary,
                       i.snippet, i.locator_json, i.freshness_seconds, i.redacted, i.truncated,
                       i.content_hash, i.collection_status, i.error_type, i.artifact_reference,
                       i.metadata_json
                  FROM koc_evidence_item i
                  JOIN koc_workflow_execution e ON e.id = i.execution_id
                 WHERE e.public_id = ?
                 ORDER BY i.observed_at ASC, i.id ASC
                """, new EvidenceRowMapper(), executionPublicId);
    }

    private final class EvidenceRowMapper implements RowMapper<EvidenceItem> {

        @Override
        public EvidenceItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvidenceItem(
                    rs.getString("public_id"),
                    rs.getString("execution_public_id"),
                    EvidenceType.valueOf(rs.getString("evidence_type")),
                    rs.getString("source"),
                    rs.getString("cluster_name"),
                    rs.getString("namespace_name"),
                    new EvidenceResource(
                            rs.getString("resource_kind"), rs.getString("resource_name"), rs.getString("resource_uid")),
                    instant(rs, "observed_at"),
                    new EvidenceWindow(instant(rs, "window_start"), instant(rs, "window_end")),
                    rs.getString("summary"),
                    rs.getString("snippet"),
                    map(rs.getString("locator_json")),
                    rs.getLong("freshness_seconds"),
                    rs.getBoolean("redacted"),
                    rs.getBoolean("truncated"),
                    rs.getString("content_hash"),
                    EvidenceCollectionStatus.valueOf(rs.getString("collection_status")),
                    rs.getString("error_type"),
                    rs.getString("artifact_reference"),
                    map(rs.getString("metadata_json")));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Evidence JSON is invalid", ex);
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean executionExists(String executionPublicId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_workflow_execution WHERE public_id = ?", Integer.class, executionPublicId);
        return count != null && count > 0;
    }
}
