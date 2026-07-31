package com.kubeoncall.approval.mysql;

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

/** Explicit-SQL approval fact repository with a versioned compare-and-set decision. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MySqlApprovalRepository {

    private static final String COLUMNS = """
            a.id, a.public_id, a.execution_id, e.public_id AS execution_public_id,
            a.action_type, a.dedupe_key, a.risk_level, a.status, a.context_json,
            a.requested_by, a.requested_at, a.decided_by, a.decided_at, a.decision,
            a.comment, a.expires_at, a.version, a.created_at, a.updated_at
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public MySqlApprovalRepository(
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

    public ApprovalRequestRecord create(CreateApproval command) {
        String publicId = publicId(command.publicId());
        Long executionId = jdbcTemplate.queryForObject(
                "SELECT id FROM koc_workflow_execution WHERE public_id = ?", Long.class, command.executionPublicId());
        if (executionId == null) {
            throw new IllegalArgumentException("Unknown workflow execution: " + command.executionPublicId());
        }
        jdbcTemplate.update(
                """
                INSERT INTO koc_approval_request
                  (public_id, execution_id, action_type, dedupe_key, risk_level, status,
                   context_json, requested_by, requested_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                """,
                publicId,
                executionId,
                command.actionType(),
                command.dedupeKey(),
                command.riskLevel(),
                json(command.context()),
                command.requestedBy(),
                command.requestedAt(),
                command.expiresAt());
        return findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("Created approval is not readable: " + publicId));
    }

    public Optional<ApprovalRequestRecord> findByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS
                            + " FROM koc_approval_request a"
                            + " JOIN koc_workflow_execution e ON e.id = a.execution_id"
                            + " WHERE a.public_id = ?",
                    new ApprovalRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public ApprovalPage list(ApprovalQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendInFilter(where, args, "a.status", query.statuses());
        appendInFilter(where, args, "a.risk_level", query.riskLevels());
        appendEqualsFilter(where, args, "e.public_id", query.executionPublicId());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_approval_request a"
                        + " JOIN koc_workflow_execution e ON e.id = a.execution_id"
                        + where,
                Long.class,
                args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        int offset = (page - 1) * size;
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add(offset);
        List<ApprovalRequestRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM koc_approval_request a"
                        + " JOIN koc_workflow_execution e ON e.id = a.execution_id"
                        + where
                        + " ORDER BY a.requested_at DESC, a.id DESC LIMIT ? OFFSET ?",
                new ApprovalRowMapper(objectMapper),
                pagedArgs.toArray());
        return new ApprovalPage(rows, count == null ? 0 : count);
    }

    /**
     * Decide a pending, unexpired request exactly once.
     *
     * <p>The update predicate includes both version and lifecycle state so concurrent approvers
     * cannot overwrite each other. A zero-row update is classified with a fresh read.
     */
    public DecisionOutcome decide(
            String publicId, long expectedVersion, String decision, long decidedBy, Instant decidedAt, String comment) {
        String normalizedDecision = normalizeDecision(decision);
        int updated = jdbcTemplate.update(
                """
                UPDATE koc_approval_request
                   SET status = ?,
                       decision = ?,
                       decided_by = ?,
                       decided_at = ?,
                       comment = ?,
                       version = version + 1
                 WHERE public_id = ?
                   AND version = ?
                   AND status = 'PENDING'
                   AND expires_at > ?
                """,
                normalizedDecision,
                normalizedDecision,
                decidedBy,
                decidedAt,
                comment,
                publicId,
                expectedVersion,
                decidedAt);
        if (updated == 1) {
            return DecisionOutcome.DECIDED;
        }
        Optional<ApprovalRequestRecord> current = findByPublicId(publicId);
        if (current.isEmpty()) {
            return DecisionOutcome.NOT_FOUND;
        }
        ApprovalRequestRecord record = current.get();
        if (record.version() != expectedVersion) {
            return DecisionOutcome.VERSION_CONFLICT;
        }
        if (!"PENDING".equals(record.status())) {
            return DecisionOutcome.NOT_PENDING;
        }
        if (!record.expiresAt().isAfter(decidedAt)) {
            jdbcTemplate.update("""
                    UPDATE koc_approval_request
                       SET status = 'EXPIRED', version = version + 1
                     WHERE public_id = ? AND version = ? AND status = 'PENDING' AND expires_at <= ?
                    """, publicId, expectedVersion, decidedAt);
            return DecisionOutcome.EXPIRED;
        }
        return DecisionOutcome.VERSION_CONFLICT;
    }

    private static String normalizeDecision(String decision) {
        String value = decision == null ? "" : decision.trim().toUpperCase();
        if (!"APPROVED".equals(value) && !"REJECTED".equals(value)) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED");
        }
        return value;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Approval context cannot be serialized", ex);
        }
    }

    private static void appendInFilter(StringBuilder where, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> filtered = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (filtered.isEmpty()) {
            return;
        }
        where.append(" AND ")
                .append(column)
                .append(" IN (")
                .append(String.join(",", java.util.Collections.nCopies(filtered.size(), "?")))
                .append(")");
        args.addAll(filtered);
    }

    private static void appendEqualsFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "apr_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static final class ApprovalRowMapper implements RowMapper<ApprovalRequestRecord> {

        private final ObjectMapper objectMapper;

        private ApprovalRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ApprovalRequestRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            long decidedByValue = rs.getLong("decided_by");
            Long decidedBy = rs.wasNull() ? null : decidedByValue;
            return new ApprovalRequestRecord(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getLong("execution_id"),
                    rs.getString("execution_public_id"),
                    rs.getString("action_type"),
                    rs.getString("dedupe_key"),
                    rs.getString("risk_level"),
                    rs.getString("status"),
                    parseMap(objectMapper, rs.getString("context_json")),
                    rs.getLong("requested_by"),
                    instant(rs, "requested_at"),
                    decidedBy,
                    instant(rs, "decided_at"),
                    rs.getString("decision"),
                    rs.getString("comment"),
                    instant(rs, "expires_at"),
                    rs.getLong("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));
        }

        private static Map<String, Object> parseMap(ObjectMapper objectMapper, String json) throws SQLException {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            try {
                return objectMapper.readValue(json, MAP_TYPE);
            } catch (JsonProcessingException ex) {
                throw new SQLException("Invalid approval context JSON", ex);
            }
        }
    }

    public enum DecisionOutcome {
        DECIDED,
        NOT_FOUND,
        VERSION_CONFLICT,
        NOT_PENDING,
        EXPIRED
    }

    public record CreateApproval(
            String publicId,
            String executionPublicId,
            String actionType,
            String dedupeKey,
            String riskLevel,
            Map<String, Object> context,
            long requestedBy,
            Instant requestedAt,
            Instant expiresAt) {}

    public record ApprovalQuery(
            int page, int size, List<String> statuses, List<String> riskLevels, String executionPublicId) {}

    public record ApprovalPage(List<ApprovalRequestRecord> rows, long total) {}
}
