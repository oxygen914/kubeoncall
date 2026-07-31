package com.kubeoncall.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Idempotency for high-risk browser/webhook writes. A record keyed by
 * {@code (principal, route, idempotency-key)} is inserted with {@code PROCESSING} before the business
 * transaction; the canonical request hash lets a retry of the same request replay the stored result
 * while a different body under the same key yields {@code IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Concurrent duplicates are caught by the unique constraint on the scope: the first insert wins,
 * followers observe a {@link DuplicateKeyException} and read the in-flight/completed record. The
 * service is intended to be called inside the same transaction as the protected business command;
 * persistence and serialization failures therefore propagate and roll the complete command back.
 */
public class IdempotencyService {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;
    private final Duration recordTtl;

    public IdempotencyService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper, Duration recordTtl) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
        this.recordTtl = recordTtl;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        return jdbcTemplate != null;
    }

    /**
     * Begin an idempotent operation. Returns a handle telling the caller whether to execute the
     * business logic ({@code EXECUTE}) or replay a stored result ({@code REPLAY}).
     */
    public BeginResult begin(IdempotencyScope scope, String idempotencyKey, String canonicalRequest) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return BeginResult.execute();
        }
        byte[] hash = sha256(canonicalRequest);
        Instant expiresAt = Instant.now().plus(recordTtl);
        deleteExpired(jdbcTemplate, scope, idempotencyKey);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO koc_idempotency_record
                      (principal_type, principal_public_id, route_key, idempotency_key,
                       request_hash, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?)
                    """,
                    scope.principalType(),
                    scope.principalPublicId(),
                    scope.routeKey(),
                    idempotencyKey,
                    hash,
                    Timestamp.from(expiresAt));
            return BeginResult.execute();
        } catch (DuplicateKeyException ex) {
            return resolveExisting(jdbcTemplate, scope, idempotencyKey, hash);
        }
    }

    /** Persist the successful result so a retry replays it. */
    public void succeed(
            IdempotencyScope scope,
            String idempotencyKey,
            int httpStatus,
            Object response,
            String resourceType,
            String resourcePublicId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return;
        }
        int updated = jdbcTemplate.update(
                """
                    UPDATE koc_idempotency_record
                       SET status = 'SUCCEEDED', http_status = ?, response_json = ?,
                           resource_type = ?, resource_public_id = ?
                     WHERE principal_type = ? AND principal_public_id = ?
                       AND route_key = ? AND idempotency_key = ?
                    """,
                httpStatus,
                toJson(response),
                resourceType,
                resourcePublicId,
                scope.principalType(),
                scope.principalPublicId(),
                scope.routeKey(),
                idempotencyKey);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record disappeared before command completion");
        }
    }

    private BeginResult resolveExisting(
            JdbcTemplate jdbcTemplate, IdempotencyScope scope, String idempotencyKey, byte[] hash) {
        Optional<StoredRecord> existing = jdbcTemplate
                .query(
                        """
                    SELECT request_hash, status, http_status, response_json
                      FROM koc_idempotency_record
                     WHERE principal_type = ? AND principal_public_id = ?
                       AND route_key = ? AND idempotency_key = ?
                    """,
                        (rs, rowNum) -> new StoredRecord(
                                rs.getBytes("request_hash"),
                                rs.getString("status"),
                                rs.getObject("http_status") == null ? null : rs.getInt("http_status"),
                                rs.getString("response_json")),
                        scope.principalType(),
                        scope.principalPublicId(),
                        scope.routeKey(),
                        idempotencyKey)
                .stream()
                .findFirst();
        if (existing.isEmpty()) {
            return BeginResult.execute();
        }
        StoredRecord record = existing.get();
        if (!MessageDigest.isEqual(record.requestHash(), hash)) {
            return BeginResult.reused();
        }
        if ("PROCESSING".equals(record.status())) {
            return BeginResult.inProgress();
        }
        return BeginResult.replay(record.httpStatus(), record.responseJson());
    }

    private void deleteExpired(JdbcTemplate jdbcTemplate, IdempotencyScope scope, String idempotencyKey) {
        jdbcTemplate.update(
                """
                DELETE FROM koc_idempotency_record
                 WHERE principal_type = ? AND principal_public_id = ?
                   AND route_key = ? AND idempotency_key = ? AND expires_at <= ?
                """,
                scope.principalType(),
                scope.principalPublicId(),
                scope.routeKey(),
                idempotencyKey,
                Timestamp.from(Instant.now()));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize idempotency response", ex);
        }
    }

    public record IdempotencyScope(String principalType, String principalPublicId, String routeKey) {}

    public record BeginResult(Action action, Integer httpStatus, String responseJson) {

        public enum Action {
            EXECUTE,
            REPLAY,
            IN_PROGRESS,
            REUSED
        }

        public static BeginResult execute() {
            return new BeginResult(Action.EXECUTE, null, null);
        }

        public static BeginResult replay(Integer httpStatus, String responseJson) {
            return new BeginResult(Action.REPLAY, httpStatus, responseJson);
        }

        public static BeginResult inProgress() {
            return new BeginResult(Action.IN_PROGRESS, null, null);
        }

        public static BeginResult reused() {
            return new BeginResult(Action.REUSED, null, null);
        }
    }

    private record StoredRecord(byte[] requestHash, String status, Integer httpStatus, String responseJson) {}
}
