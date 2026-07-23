package com.kubeoncall.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MySQL async-task repository with transactional claim, renewable leases and fencing.
 *
 * <p>Every write made by a worker checks the random owner token, monotonically increasing fencing
 * token and an unexpired lease. A worker that pauses past its lease therefore cannot overwrite a
 * result written after another worker reclaimed the task.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class AsyncTaskRepository {

    private static final String COLUMNS = """
            t.id, t.public_id, t.task_type, t.resource_type, t.resource_public_id,
            t.dedupe_key, t.status, t.stage, t.progress, t.request_json, t.result_json,
            t.error_code, t.error_summary, t.owner_token, t.lease_until, t.fencing_token,
            t.attempt, t.max_attempts, t.next_attempt_at, t.started_at, t.finished_at,
            t.request_id, t.trace_id, t.version, t.created_at, t.updated_at
            """;

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public AsyncTaskRepository(
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

    public AsyncTaskRecord create(CreateTask command) {
        String publicId = publicId(command.publicId());
        int maxAttempts = command.maxAttempts() <= 0 ? 5 : command.maxAttempts();
        Instant nextAttemptAt = command.nextAttemptAt() == null ? Instant.now() : command.nextAttemptAt();
        jdbcTemplate.update(
                """
                INSERT INTO koc_async_task
                  (public_id, task_type, resource_type, resource_public_id, dedupe_key,
                   status, stage, progress, request_json, max_attempts, next_attempt_at,
                   request_id, trace_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 0, ?, ?, ?, ?, ?)
                """,
                publicId,
                command.taskType(),
                command.resourceType(),
                command.resourcePublicId(),
                command.dedupeKey(),
                command.stage(),
                json(command.request()),
                maxAttempts,
                nextAttemptAt,
                command.requestId(),
                command.traceId());
        return findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("Created async task is not readable: " + publicId));
    }

    public Optional<AsyncTaskRecord> findByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM koc_async_task t WHERE t.public_id = ?",
                    new TaskRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Claims either a due task or an expired RUNNING task.
     *
     * <p>The selected row remains locked until the transaction commits. Reclaim increments both
     * attempt and fencing token, invalidating every write capability held by the previous owner.
     */
    @Transactional
    public Optional<AsyncTaskRecord> claimNext(
            String ownerToken, Instant now, Duration leaseDuration, Set<String> taskTypes) {
        requireOwner(ownerToken);
        Instant leaseUntil = requireLease(now, leaseDuration);
        List<Object> args = new ArrayList<>();
        args.add(now);
        args.add(now);
        StringBuilder sql = new StringBuilder("""
                SELECT id
                  FROM koc_async_task
                 WHERE attempt < max_attempts
                   AND (
                     (status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?)
                     OR (status = 'RUNNING' AND lease_until <= ?)
                   )
                """);
        List<String> filteredTypes = taskTypes == null
                ? List.of()
                : taskTypes.stream()
                        .filter(type -> type != null && !type.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
        if (!filteredTypes.isEmpty()) {
            sql.append(" AND task_type IN (")
                    .append(String.join(",", java.util.Collections.nCopies(filteredTypes.size(), "?")))
                    .append(")");
            args.addAll(filteredTypes);
        }
        sql.append(" ORDER BY next_attempt_at ASC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED");
        List<Long> candidates = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getLong("id"), args.toArray());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        long taskId = candidates.get(0);
        int updated = jdbcTemplate.update("""
                UPDATE koc_async_task
                   SET status = 'RUNNING',
                       owner_token = ?,
                       lease_until = ?,
                       fencing_token = fencing_token + 1,
                       attempt = attempt + 1,
                       started_at = COALESCE(started_at, ?),
                       finished_at = NULL,
                       version = version + 1
                 WHERE id = ?
                   AND attempt < max_attempts
                   AND (
                     (status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?)
                     OR (status = 'RUNNING' AND lease_until <= ?)
                   )
                """, ownerToken, leaseUntil, now, taskId, now, now);
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(taskId);
    }

    public boolean heartbeat(
            String publicId, String ownerToken, long fencingToken, Instant now, Duration leaseDuration) {
        requireOwnership(ownerToken, fencingToken);
        Instant leaseUntil = requireLease(now, leaseDuration);
        return ownedUpdate("""
                UPDATE koc_async_task
                   SET lease_until = ?, version = version + 1
                 WHERE public_id = ?
                   AND status = 'RUNNING'
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, leaseUntil, publicId, ownerToken, fencingToken, now);
    }

    /**
     * Cancels a queued, retrying or running task. A running handler observes the changed durable
     * state through {@link #isCancelled(String)} before its next source-item commit; its stale
     * completion write is rejected by the status predicate.
     */
    public boolean cancel(String publicId, Instant now) {
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("publicId is required");
        }
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return jdbcTemplate.update("""
                        UPDATE koc_async_task
                           SET status = 'CANCELLED',
                               error_code = 'CANCELLED',
                               error_summary = 'Cancelled by operator',
                               finished_at = ?,
                               owner_token = NULL,
                               lease_until = NULL,
                               version = version + 1
                         WHERE public_id = ?
                           AND status IN ('PENDING', 'RETRY', 'RUNNING')
                        """, now, publicId) == 1;
    }

    /** Cheap cooperative-cancellation probe for a long-running task handler. */
    public boolean isCancelled(String publicId) {
        Boolean cancelled = jdbcTemplate.queryForObject(
                "SELECT status = 'CANCELLED' FROM koc_async_task WHERE public_id = ?", Boolean.class, publicId);
        return Boolean.TRUE.equals(cancelled);
    }

    /**
     * Updates a running task's user-visible stage and progress under the same owner/fencing/lease
     * predicate as every other worker write.
     */
    public boolean updateProgress(
            String publicId, String ownerToken, long fencingToken, String stage, int progress, Instant now) {
        requireOwnership(ownerToken, fencingToken);
        if (progress < 0 || progress > 99) {
            throw new IllegalArgumentException("progress must be between 0 and 99");
        }
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        return ownedUpdate("""
                UPDATE koc_async_task
                   SET stage = ?,
                       progress = ?,
                       version = version + 1
                 WHERE public_id = ?
                   AND status = 'RUNNING'
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, stage, progress, publicId, ownerToken, fencingToken, now);
    }

    public boolean complete(
            String publicId, String ownerToken, long fencingToken, Map<String, Object> result, Instant now) {
        requireOwnership(ownerToken, fencingToken);
        return ownedUpdate("""
                UPDATE koc_async_task
                   SET status = 'SUCCEEDED',
                       progress = 100,
                       result_json = ?,
                       error_code = NULL,
                       error_summary = NULL,
                       finished_at = ?,
                       owner_token = NULL,
                       lease_until = NULL,
                       version = version + 1
                 WHERE public_id = ?
                   AND status = 'RUNNING'
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, json(result), now, publicId, ownerToken, fencingToken, now);
    }

    public boolean fail(
            String publicId, String ownerToken, long fencingToken, String errorCode, String errorSummary, Instant now) {
        requireOwnership(ownerToken, fencingToken);
        return ownedUpdate("""
                UPDATE koc_async_task
                   SET status = 'FAILED',
                       error_code = ?,
                       error_summary = ?,
                       finished_at = ?,
                       owner_token = NULL,
                       lease_until = NULL,
                       version = version + 1
                 WHERE public_id = ?
                   AND status = 'RUNNING'
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, errorCode, errorSummary, now, publicId, ownerToken, fencingToken, now);
    }

    public boolean retry(
            String publicId,
            String ownerToken,
            long fencingToken,
            String errorCode,
            String errorSummary,
            Instant nextAttemptAt,
            Instant now) {
        requireOwnership(ownerToken, fencingToken);
        if (nextAttemptAt == null || !nextAttemptAt.isAfter(now)) {
            throw new IllegalArgumentException("nextAttemptAt must be after now");
        }
        return ownedUpdate("""
                UPDATE koc_async_task
                   SET status = 'RETRY',
                       error_code = ?,
                       error_summary = ?,
                       next_attempt_at = ?,
                       finished_at = NULL,
                       owner_token = NULL,
                       lease_until = NULL,
                       version = version + 1
                 WHERE public_id = ?
                   AND status = 'RUNNING'
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                   AND attempt < max_attempts
                """, errorCode, errorSummary, nextAttemptAt, publicId, ownerToken, fencingToken, now);
    }

    public boolean deadLetter(
            String publicId, String ownerToken, long fencingToken, String errorCode, String errorSummary, Instant now) {
        requireOwnership(ownerToken, fencingToken);
        return ownedUpdate("""
                UPDATE koc_async_task
                   SET status = 'DEAD_LETTER',
                       error_code = ?,
                       error_summary = ?,
                       finished_at = ?,
                       owner_token = NULL,
                       lease_until = NULL,
                       version = version + 1
                 WHERE public_id = ?
                   AND status = 'RUNNING'
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, errorCode, errorSummary, now, publicId, ownerToken, fencingToken, now);
    }

    private Optional<AsyncTaskRecord> findById(long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + " FROM koc_async_task t WHERE t.id = ?",
                    new TaskRowMapper(objectMapper),
                    id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private boolean ownedUpdate(String sql, Object... args) {
        return jdbcTemplate.update(sql, args) == 1;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Task payload cannot be serialized", ex);
        }
    }

    private static void requireOwner(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken is required");
        }
    }

    private static void requireOwnership(String ownerToken, long fencingToken) {
        requireOwner(ownerToken);
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    private static Instant requireLease(Instant now, Duration leaseDuration) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return now.plus(leaseDuration);
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "tsk_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private static final class TaskRowMapper implements RowMapper<AsyncTaskRecord> {

        private final ObjectMapper objectMapper;

        private TaskRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public AsyncTaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AsyncTaskRecord(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getString("task_type"),
                    rs.getString("resource_type"),
                    rs.getString("resource_public_id"),
                    rs.getString("dedupe_key"),
                    rs.getString("status"),
                    rs.getString("stage"),
                    rs.getInt("progress"),
                    parseMap(objectMapper, rs.getString("request_json")),
                    parseMap(objectMapper, rs.getString("result_json")),
                    rs.getString("error_code"),
                    rs.getString("error_summary"),
                    rs.getString("owner_token"),
                    instant(rs, "lease_until"),
                    rs.getLong("fencing_token"),
                    rs.getInt("attempt"),
                    rs.getInt("max_attempts"),
                    instant(rs, "next_attempt_at"),
                    instant(rs, "started_at"),
                    instant(rs, "finished_at"),
                    rs.getString("request_id"),
                    rs.getString("trace_id"),
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
                throw new SQLException("Invalid async-task JSON", ex);
            }
        }
    }

    public record CreateTask(
            String publicId,
            String taskType,
            String resourceType,
            String resourcePublicId,
            String dedupeKey,
            String stage,
            Map<String, Object> request,
            int maxAttempts,
            Instant nextAttemptAt,
            String requestId,
            String traceId) {}
}
