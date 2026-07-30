package com.kubeoncall.agent.executor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.observability.SensitiveDataRedactor;

/** MySQL read/audit projection for operation closure and human-escalation facts. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class OperationClosureFactRepository {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SensitiveDataRedactor redactor = SensitiveDataRedactor.STANDARD;

    public OperationClosureFactRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsertClosure(
            String operationId,
            String executionId,
            String taskId,
            String executorKind,
            String action,
            String target,
            String phase,
            Map<String, Object> details,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO koc_operation_closure_fact
                  (public_id, operation_id, execution_public_id, task_public_id, executor_kind,
                   action_name, target_name, phase, details_json, error_summary, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  phase = VALUES(phase),
                  details_json = VALUES(details_json),
                  error_summary = VALUES(error_summary),
                  finished_at = COALESCE(VALUES(finished_at), finished_at),
                  version = version + 1
                """,
                publicId("opc_", operationId),
                requireText(operationId, "operationId"),
                requireText(executionId, "executionId"),
                blankToNull(taskId),
                requireText(executorKind, "executorKind"),
                requireText(action, "action"),
                blankToNull(target),
                requireText(phase, "phase"),
                toJson(details),
                bounded(errorSummary),
                Timestamp.from(startedAt == null ? Instant.now() : startedAt),
                finishedAt == null ? null : Timestamp.from(finishedAt));
    }

    public void upsertEscalation(
            String operationId,
            String executionId,
            String status,
            String severity,
            String summary,
            Map<String, Object> details,
            String lastErrorSummary) {
        jdbcTemplate.update(
                """
                INSERT INTO koc_operation_escalation_fact
                  (public_id, operation_id, execution_public_id, status, severity, summary,
                   details_json, last_error_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  status = VALUES(status),
                  severity = VALUES(severity),
                  summary = VALUES(summary),
                  details_json = VALUES(details_json),
                  last_error_summary = VALUES(last_error_summary),
                  version = version + 1
                """,
                publicId("ope_", operationId),
                requireText(operationId, "operationId"),
                requireText(executionId, "executionId"),
                requireText(status, "status"),
                requireText(severity, "severity"),
                requireText(summary, "summary"),
                toJson(details),
                bounded(lastErrorSummary));
    }

    public Optional<ClosureFact> findLatestClosure(String executionId) {
        List<ClosureFact> rows =
                jdbcTemplate.query("""
                SELECT public_id, operation_id, execution_public_id, task_public_id, executor_kind,
                       action_name, target_name, phase, details_json, error_summary, started_at,
                       finished_at, version, created_at, updated_at
                  FROM koc_operation_closure_fact
                 WHERE execution_public_id = ?
                 ORDER BY updated_at DESC, id DESC
                 LIMIT 1
                """, new ClosureRowMapper(), requireText(executionId, "executionId"));
        return rows.stream().findFirst();
    }

    public Optional<EscalationFact> findEscalation(String operationId) {
        List<EscalationFact> rows =
                jdbcTemplate.query("""
                SELECT public_id, operation_id, execution_public_id, status, severity, summary,
                       details_json, last_error_summary, version, created_at, updated_at
                  FROM koc_operation_escalation_fact
                 WHERE operation_id = ?
                """, new EscalationRowMapper(), requireText(operationId, "operationId"));
        return rows.stream().findFirst();
    }

    private String toJson(Map<String, Object> details) {
        try {
            Map<String, Object> safe = details == null ? Map.of() : redactor.redactMap(details);
            return objectMapper.writeValueAsString(safe);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to serialize operation closure facts", exception);
        }
    }

    private Map<String, Object> fromJson(String raw) throws SQLException {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(raw, OBJECT_MAP);
            return value == null ? Map.of() : new LinkedHashMap<>(value);
        } catch (Exception exception) {
            throw new SQLException("Failed to decode operation closure facts", exception);
        }
    }

    private static String publicId(String prefix, String operationId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(requireText(operationId, "operationId").getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= MAX_ERROR_LENGTH ? normalized : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record ClosureFact(
            String id,
            String operationId,
            String executionId,
            String taskId,
            String executorKind,
            String action,
            String target,
            String phase,
            Map<String, Object> details,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record EscalationFact(
            String id,
            String operationId,
            String executionId,
            String status,
            String severity,
            String summary,
            Map<String, Object> details,
            String lastErrorSummary,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    private final class ClosureRowMapper implements RowMapper<ClosureFact> {

        @Override
        public ClosureFact mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return new ClosureFact(
                    resultSet.getString("public_id"),
                    resultSet.getString("operation_id"),
                    resultSet.getString("execution_public_id"),
                    resultSet.getString("task_public_id"),
                    resultSet.getString("executor_kind"),
                    resultSet.getString("action_name"),
                    resultSet.getString("target_name"),
                    resultSet.getString("phase"),
                    fromJson(resultSet.getString("details_json")),
                    resultSet.getString("error_summary"),
                    instant(resultSet, "started_at"),
                    instant(resultSet, "finished_at"),
                    resultSet.getLong("version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"));
        }
    }

    private final class EscalationRowMapper implements RowMapper<EscalationFact> {

        @Override
        public EscalationFact mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return new EscalationFact(
                    resultSet.getString("public_id"),
                    resultSet.getString("operation_id"),
                    resultSet.getString("execution_public_id"),
                    resultSet.getString("status"),
                    resultSet.getString("severity"),
                    resultSet.getString("summary"),
                    fromJson(resultSet.getString("details_json")),
                    resultSet.getString("last_error_summary"),
                    resultSet.getLong("version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"));
        }
    }
}
