package com.kubeoncall.audit.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lease-based JDBC repository for {@code koc_outbox_event}.
 *
 * <p>Claiming only holds row locks while assigning the lease. Handler execution happens outside
 * this repository transaction. Every renewal and terminal transition is fenced by both
 * {@code owner_token} and a still-valid {@code lease_until}; a stale owner therefore updates zero
 * rows.
 */
public class OutboxRepository {

    private static final int MAX_BATCH_SIZE = 1000;
    private static final int MAX_OWNER_TOKEN_LENGTH = 128;
    private static final int MAX_ERROR_CODE_LENGTH = 128;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactionTemplate =
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * Claims due pending events and expired leases in one short transaction.
     *
     * <p>MySQL 8 {@code FOR UPDATE SKIP LOCKED} permits multiple workers to claim disjoint batches.
     */
    public List<OutboxEvent> claimBatch(String ownerToken, Instant now, Duration leaseDuration, int batchSize) {
        String owner = requireOwnerToken(ownerToken);
        Instant claimedAt = Objects.requireNonNull(now, "now");
        Duration lease = requirePositive(leaseDuration, "leaseDuration");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        Instant leaseUntil = claimedAt.plus(lease);

        List<OutboxEvent> claimed = transactionTemplate.execute(status -> {
            List<OutboxEvent> candidates = jdbcTemplate.query(
                    """
                    SELECT id, event_id, aggregate_type, aggregate_public_id, event_type,
                           schema_version, payload_json, request_id, attempt, max_attempts,
                           created_at, lease_until
                      FROM koc_outbox_event
                     WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                        OR (status = 'PROCESSING' AND lease_until <= ?)
                     ORDER BY id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                    """, new EventRowMapper(), Timestamp.from(claimedAt), Timestamp.from(claimedAt), batchSize);

            List<OutboxEvent> result = new ArrayList<>(candidates.size());
            for (OutboxEvent candidate : candidates) {
                if (candidate.attempt() >= candidate.maxAttempts()) {
                    deadLetterExhausted(candidate.id(), owner, claimedAt, leaseUntil);
                    continue;
                }
                int updated = jdbcTemplate.update(
                        """
                        UPDATE koc_outbox_event
                           SET status = 'PROCESSING',
                               attempt = attempt + 1,
                               owner_token = ?,
                               lease_until = ?,
                               updated_at = ?
                         WHERE id = ?
                        """, owner, Timestamp.from(leaseUntil), Timestamp.from(claimedAt), candidate.id());
                requireSingleRow(updated, candidate.id(), "claim");
                result.add(withClaim(candidate, leaseUntil));
            }
            return List.copyOf(result);
        });
        return claimed == null ? List.of() : claimed;
    }

    /** Renews only an active lease still owned by {@code ownerToken}. */
    public boolean renewLease(long eventId, String ownerToken, Instant now, Duration leaseDuration) {
        requirePositiveId(eventId);
        String owner = requireOwnerToken(ownerToken);
        Instant renewedAt = Objects.requireNonNull(now, "now");
        Instant leaseUntil = renewedAt.plus(requirePositive(leaseDuration, "leaseDuration"));
        return jdbcTemplate.update(
                        """
                        UPDATE koc_outbox_event
                           SET lease_until = ?, updated_at = ?
                         WHERE id = ?
                           AND status = 'PROCESSING'
                           AND owner_token = ?
                           AND lease_until > ?
                        """,
                        Timestamp.from(leaseUntil),
                        Timestamp.from(renewedAt),
                        eventId,
                        owner,
                        Timestamp.from(renewedAt))
                == 1;
    }

    /** Marks an event published only while the caller still owns a valid lease. */
    public boolean markPublished(long eventId, String ownerToken, Instant now) {
        requirePositiveId(eventId);
        String owner = requireOwnerToken(ownerToken);
        Instant publishedAt = Objects.requireNonNull(now, "now");
        return jdbcTemplate.update(
                        """
                        UPDATE koc_outbox_event
                           SET status = 'PUBLISHED',
                               published_at = ?,
                               owner_token = NULL,
                               lease_until = NULL,
                               last_error_code = NULL,
                               last_error_summary = NULL,
                               updated_at = ?
                         WHERE id = ?
                           AND status = 'PROCESSING'
                           AND owner_token = ?
                           AND lease_until > ?
                        """,
                        Timestamp.from(publishedAt),
                        Timestamp.from(publishedAt),
                        eventId,
                        owner,
                        Timestamp.from(publishedAt))
                == 1;
    }

    /**
     * Records a failed attempt, schedules capped exponential backoff, or dead-letters the event
     * after its final attempt.
     */
    public FailureDisposition markFailure(
            long eventId,
            String ownerToken,
            Instant now,
            String errorCode,
            String errorSummary,
            Duration initialBackoff,
            Duration maxBackoff) {
        requirePositiveId(eventId);
        String owner = requireOwnerToken(ownerToken);
        Instant failedAt = Objects.requireNonNull(now, "now");
        Duration initial = requirePositive(initialBackoff, "initialBackoff");
        Duration maximum = requirePositive(maxBackoff, "maxBackoff");
        if (maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        String code = bounded(errorCode, "HANDLER_FAILURE", MAX_ERROR_CODE_LENGTH);
        String summary = bounded(errorSummary, "Outbox handler failed", MAX_ERROR_SUMMARY_LENGTH);

        FailureDisposition disposition = transactionTemplate.execute(status -> {
            AttemptState attemptState = loadOwnedAttemptForUpdate(eventId, owner, failedAt);
            if (attemptState == null) {
                return FailureDisposition.LEASE_LOST;
            }
            if (attemptState.attempt() >= attemptState.maxAttempts()) {
                int updated = jdbcTemplate.update(
                        """
                        UPDATE koc_outbox_event
                           SET status = 'DEAD_LETTER',
                               owner_token = NULL,
                               lease_until = NULL,
                               last_error_code = ?,
                               last_error_summary = ?,
                               updated_at = ?
                         WHERE id = ?
                           AND status = 'PROCESSING'
                           AND owner_token = ?
                           AND lease_until > ?
                        """, code, summary, Timestamp.from(failedAt), eventId, owner, Timestamp.from(failedAt));
                return updated == 1 ? FailureDisposition.DEAD_LETTERED : FailureDisposition.LEASE_LOST;
            }

            Instant nextAttemptAt = failedAt.plus(exponentialBackoff(initial, maximum, attemptState.attempt()));
            int updated = jdbcTemplate.update(
                    """
                    UPDATE koc_outbox_event
                       SET status = 'PENDING',
                           next_attempt_at = ?,
                           owner_token = NULL,
                           lease_until = NULL,
                           last_error_code = ?,
                           last_error_summary = ?,
                           updated_at = ?
                     WHERE id = ?
                       AND status = 'PROCESSING'
                       AND owner_token = ?
                       AND lease_until > ?
                    """,
                    Timestamp.from(nextAttemptAt),
                    code,
                    summary,
                    Timestamp.from(failedAt),
                    eventId,
                    owner,
                    Timestamp.from(failedAt));
            return updated == 1 ? FailureDisposition.RETRY_SCHEDULED : FailureDisposition.LEASE_LOST;
        });
        return disposition == null ? FailureDisposition.LEASE_LOST : disposition;
    }

    private AttemptState loadOwnedAttemptForUpdate(long eventId, String owner, Instant now) {
        List<AttemptState> rows = jdbcTemplate.query(
                """
                SELECT attempt, max_attempts
                  FROM koc_outbox_event
                 WHERE id = ?
                   AND status = 'PROCESSING'
                   AND owner_token = ?
                   AND lease_until > ?
                 FOR UPDATE
                """,
                (resultSet, rowNum) -> new AttemptState(resultSet.getInt("attempt"), resultSet.getInt("max_attempts")),
                eventId,
                owner,
                Timestamp.from(now));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void deadLetterExhausted(long eventId, String owner, Instant now, Instant leaseUntil) {
        int claimed = jdbcTemplate.update("""
                UPDATE koc_outbox_event
                   SET status = 'PROCESSING',
                       owner_token = ?,
                       lease_until = ?,
                       updated_at = ?
                 WHERE id = ?
                """, owner, Timestamp.from(leaseUntil), Timestamp.from(now), eventId);
        requireSingleRow(claimed, eventId, "claim exhausted event");

        int terminal = jdbcTemplate.update("""
                UPDATE koc_outbox_event
                   SET status = 'DEAD_LETTER',
                       owner_token = NULL,
                       lease_until = NULL,
                       last_error_code = 'MAX_ATTEMPTS_EXHAUSTED',
                       last_error_summary = 'Outbox lease expired after the maximum attempt',
                       updated_at = ?
                 WHERE id = ?
                   AND status = 'PROCESSING'
                   AND owner_token = ?
                   AND lease_until > ?
                """, Timestamp.from(now), eventId, owner, Timestamp.from(now));
        requireSingleRow(terminal, eventId, "dead-letter exhausted event");
    }

    static Duration exponentialBackoff(Duration initial, Duration maximum, int attempt) {
        Duration backoff = initial;
        for (int exponent = 1; exponent < Math.max(1, attempt); exponent++) {
            if (backoff.compareTo(maximum.dividedBy(2)) > 0) {
                return maximum;
            }
            backoff = backoff.multipliedBy(2);
        }
        return backoff.compareTo(maximum) > 0 ? maximum : backoff;
    }

    private static OutboxEvent withClaim(OutboxEvent event, Instant leaseUntil) {
        return new OutboxEvent(
                event.id(),
                event.eventId(),
                event.aggregateType(),
                event.aggregatePublicId(),
                event.eventType(),
                event.schemaVersion(),
                event.payloadJson(),
                event.requestId(),
                event.attempt() + 1,
                event.maxAttempts(),
                event.createdAt(),
                leaseUntil);
    }

    private static String requireOwnerToken(String ownerToken) {
        String owner = ownerToken == null ? "" : ownerToken.trim();
        if (owner.isEmpty() || owner.length() > MAX_OWNER_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "ownerToken must contain between 1 and " + MAX_OWNER_TOKEN_LENGTH + " characters");
        }
        return owner;
    }

    private static Duration requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return duration;
    }

    private static void requirePositiveId(long eventId) {
        if (eventId < 1) {
            throw new IllegalArgumentException("eventId must be positive");
        }
    }

    private static String bounded(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static void requireSingleRow(int updated, long eventId, String operation) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Outbox " + operation + " expected one row for event " + eventId + " but updated " + updated);
        }
    }

    public enum FailureDisposition {
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        LEASE_LOST
    }

    private record AttemptState(int attempt, int maxAttempts) {}

    private static final class EventRowMapper implements RowMapper<OutboxEvent> {

        @Override
        public OutboxEvent mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            Timestamp createdAt = resultSet.getTimestamp("created_at");
            Timestamp leaseUntil = resultSet.getTimestamp("lease_until");
            return new OutboxEvent(
                    resultSet.getLong("id"),
                    resultSet.getString("event_id"),
                    resultSet.getString("aggregate_type"),
                    resultSet.getString("aggregate_public_id"),
                    resultSet.getString("event_type"),
                    resultSet.getInt("schema_version"),
                    resultSet.getString("payload_json"),
                    resultSet.getString("request_id"),
                    resultSet.getInt("attempt"),
                    resultSet.getInt("max_attempts"),
                    createdAt == null ? null : createdAt.toInstant(),
                    leaseUntil == null ? null : leaseUntil.toInstant());
        }
    }
}
