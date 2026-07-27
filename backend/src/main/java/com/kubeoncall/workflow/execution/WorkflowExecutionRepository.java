package com.kubeoncall.workflow.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Explicit-SQL MySQL repository for workflow and node execution facts.
 *
 * <p>Full graph state is intentionally not persisted here; {@code graph_state_key} points at the
 * resumable Redis snapshot while these rows provide durable list/detail/audit summaries.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class WorkflowExecutionRepository {

    private static final String EXECUTION_COLUMNS = """
            e.id, e.public_id, e.type, e.trigger_type, e.trigger_public_id, e.dedupe_key,
            e.status, e.risk_level, e.summary, e.result_summary, e.error_code, e.error_summary,
            e.actor_type, e.actor_id, e.session_id, e.request_id, e.trace_id, e.graph_state_key,
            e.started_at, e.finished_at, e.version, e.created_at, e.updated_at
            """;

    private static final String NODE_COLUMNS = """
            n.id, n.public_id, n.execution_id, e.public_id AS execution_public_id,
            n.node_name, n.node_type, n.attempt, n.status, n.input_summary, n.output_summary,
            n.error_code, n.error_summary, n.started_at, n.finished_at, n.duration_ms,
            n.version, n.created_at, n.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysqlEnabled;

    public WorkflowExecutionRepository(
            JdbcTemplate jdbcTemplate, @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    public WorkflowExecutionRecord create(CreateExecution command) {
        String publicId = publicId(command.publicId(), "exe_");
        String status = defaultValue(command.status(), "PENDING");
        jdbcTemplate.update(
                """
                INSERT INTO koc_workflow_execution
                  (public_id, type, trigger_type, trigger_public_id, dedupe_key, status,
                   risk_level, summary, actor_type, actor_id, session_id, request_id,
                   trace_id, graph_state_key, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId,
                command.type(),
                command.triggerType(),
                command.triggerPublicId(),
                command.dedupeKey(),
                status,
                command.riskLevel(),
                command.summary(),
                command.actorType(),
                command.actorId(),
                command.sessionId(),
                command.requestId(),
                command.traceId(),
                command.graphStateKey(),
                command.startedAt());
        return findByPublicId(publicId)
                .orElseThrow(
                        () -> new IllegalStateException("Created workflow execution is not readable: " + publicId));
    }

    public Optional<WorkflowExecutionRecord> findByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + EXECUTION_COLUMNS + " FROM koc_workflow_execution e WHERE e.public_id = ?",
                    new ExecutionRowMapper(),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public ExecutionPage list(ExecutionQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendInFilter(where, args, "e.status", query.statuses());
        appendInFilter(where, args, "e.type", query.types());
        appendEqualsFilter(where, args, "e.trigger_type", query.triggerType());
        appendEqualsFilter(where, args, "e.trigger_public_id", query.triggerPublicId());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_workflow_execution e" + where, Long.class, args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        int offset = (page - 1) * size;
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add(offset);
        List<WorkflowExecutionRecord> rows = jdbcTemplate.query(
                "SELECT " + EXECUTION_COLUMNS + " FROM koc_workflow_execution e" + where
                        + " ORDER BY e.created_at DESC, e.id DESC LIMIT ? OFFSET ?",
                new ExecutionRowMapper(),
                pagedArgs.toArray());
        return new ExecutionPage(rows, count == null ? 0 : count);
    }

    public boolean updateStatus(
            String publicId,
            long expectedVersion,
            String status,
            String resultSummary,
            String errorCode,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt) {
        int updated = jdbcTemplate.update(
                """
                UPDATE koc_workflow_execution
                   SET status = ?,
                       result_summary = ?,
                       error_code = ?,
                       error_summary = ?,
                       started_at = COALESCE(started_at, ?),
                       finished_at = ?,
                       version = version + 1
                 WHERE public_id = ? AND version = ?
                """, status, resultSummary, errorCode, errorSummary, startedAt, finishedAt, publicId, expectedVersion);
        return updated == 1;
    }

    public WorkflowNodeExecutionRecord createNode(CreateNodeExecution command) {
        String publicId = publicId(command.publicId(), "node_");
        Long executionId = jdbcTemplate.queryForObject(
                "SELECT id FROM koc_workflow_execution WHERE public_id = ?", Long.class, command.executionPublicId());
        if (executionId == null) {
            throw new IllegalArgumentException("Unknown workflow execution: " + command.executionPublicId());
        }
        jdbcTemplate.update(
                """
                INSERT INTO koc_workflow_node_execution
                  (public_id, execution_id, node_name, node_type, attempt, status,
                   input_summary, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId,
                executionId,
                command.nodeName(),
                command.nodeType(),
                command.attempt(),
                defaultValue(command.status(), "PENDING"),
                command.inputSummary(),
                command.startedAt());
        return findNodeByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("Created workflow node is not readable: " + publicId));
    }

    public Optional<WorkflowNodeExecutionRecord> findNodeByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + NODE_COLUMNS
                            + " FROM koc_workflow_node_execution n"
                            + " JOIN koc_workflow_execution e ON e.id = n.execution_id"
                            + " WHERE n.public_id = ?",
                    new NodeRowMapper(),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public boolean updateNode(
            String publicId,
            long expectedVersion,
            String status,
            String outputSummary,
            String errorCode,
            String errorSummary,
            Instant finishedAt) {
        int updated = jdbcTemplate.update(
                """
                UPDATE koc_workflow_node_execution
                   SET status = ?,
                       output_summary = ?,
                       error_code = ?,
                       error_summary = ?,
                       finished_at = ?,
                       duration_ms = CASE
                         WHEN started_at IS NULL OR ? IS NULL THEN NULL
                         ELSE TIMESTAMPDIFF(MICROSECOND, started_at, ?) DIV 1000
                       END,
                       version = version + 1
                 WHERE public_id = ? AND version = ?
                """,
                status,
                outputSummary,
                errorCode,
                errorSummary,
                finishedAt,
                finishedAt,
                finishedAt,
                publicId,
                expectedVersion);
        return updated == 1;
    }

    public List<WorkflowNodeExecutionRecord> listNodes(String executionPublicId) {
        return jdbcTemplate.query(
                "SELECT " + NODE_COLUMNS
                        + " FROM koc_workflow_node_execution n"
                        + " JOIN koc_workflow_execution e ON e.id = n.execution_id"
                        + " WHERE e.public_id = ? ORDER BY n.created_at ASC, n.id ASC",
                new NodeRowMapper(),
                executionPublicId);
    }

    /** Paged operational view used for notification and other node-delivery histories. */
    public NodePage listNodesByName(String nodeName, int requestedPage, int requestedSize) {
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 100));
        int offset = (page - 1) * size;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_workflow_node_execution WHERE node_name = ?", Long.class, nodeName);
        List<WorkflowNodeExecutionRecord> rows = jdbcTemplate.query(
                "SELECT " + NODE_COLUMNS
                        + " FROM koc_workflow_node_execution n"
                        + " JOIN koc_workflow_execution e ON e.id = n.execution_id"
                        + " WHERE n.node_name = ?"
                        + " ORDER BY n.created_at DESC, n.id DESC LIMIT ? OFFSET ?",
                new NodeRowMapper(),
                nodeName,
                size,
                offset);
        return new NodePage(rows, count == null ? 0 : count);
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
                .append(placeholders(filtered.size()))
                .append(")");
        args.addAll(filtered);
    }

    private static void appendEqualsFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String publicId(String requested, String prefix) {
        return requested == null || requested.isBlank()
                ? prefix + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static final class ExecutionRowMapper implements RowMapper<WorkflowExecutionRecord> {

        @Override
        public WorkflowExecutionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            long actorIdValue = rs.getLong("actor_id");
            Long actorId = rs.wasNull() ? null : actorIdValue;
            return new WorkflowExecutionRecord(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getString("type"),
                    rs.getString("trigger_type"),
                    rs.getString("trigger_public_id"),
                    rs.getString("dedupe_key"),
                    rs.getString("status"),
                    rs.getString("risk_level"),
                    rs.getString("summary"),
                    rs.getString("result_summary"),
                    rs.getString("error_code"),
                    rs.getString("error_summary"),
                    rs.getString("actor_type"),
                    actorId,
                    rs.getString("session_id"),
                    rs.getString("request_id"),
                    rs.getString("trace_id"),
                    rs.getString("graph_state_key"),
                    instant(rs, "started_at"),
                    instant(rs, "finished_at"),
                    rs.getLong("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));
        }
    }

    private static final class NodeRowMapper implements RowMapper<WorkflowNodeExecutionRecord> {

        @Override
        public WorkflowNodeExecutionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            long durationValue = rs.getLong("duration_ms");
            Long durationMs = rs.wasNull() ? null : durationValue;
            return new WorkflowNodeExecutionRecord(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getLong("execution_id"),
                    rs.getString("execution_public_id"),
                    rs.getString("node_name"),
                    rs.getString("node_type"),
                    rs.getInt("attempt"),
                    rs.getString("status"),
                    rs.getString("input_summary"),
                    rs.getString("output_summary"),
                    rs.getString("error_code"),
                    rs.getString("error_summary"),
                    instant(rs, "started_at"),
                    instant(rs, "finished_at"),
                    durationMs,
                    rs.getLong("version"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at"));
        }
    }

    public record CreateExecution(
            String publicId,
            String type,
            String triggerType,
            String triggerPublicId,
            String dedupeKey,
            String status,
            String riskLevel,
            String summary,
            String actorType,
            Long actorId,
            String sessionId,
            String requestId,
            String traceId,
            String graphStateKey,
            Instant startedAt) {}

    public record CreateNodeExecution(
            String publicId,
            String executionPublicId,
            String nodeName,
            String nodeType,
            int attempt,
            String status,
            String inputSummary,
            Instant startedAt) {}

    public record ExecutionQuery(
            int page,
            int size,
            List<String> statuses,
            List<String> types,
            String triggerType,
            String triggerPublicId) {}

    public record ExecutionPage(List<WorkflowExecutionRecord> rows, long total) {}

    public record NodePage(List<WorkflowNodeExecutionRecord> rows, long total) {}
}
