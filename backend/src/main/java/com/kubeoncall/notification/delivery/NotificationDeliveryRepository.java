package com.kubeoncall.notification.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.notification.application.NotificationDeliveryResult;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;

/** JDBC repository for the durable per-destination notification ledger. */
public class NotificationDeliveryRepository {

    private static final int MAX_ERROR_CODE_LENGTH = 128;
    private static final int MAX_ERROR_SUMMARY_LENGTH = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DeliveryRowMapper rowMapper = new DeliveryRowMapper();

    public NotificationDeliveryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Creates the delivery once. A duplicate deterministic key returns the existing record without
     * changing its state or enqueueing another Outbox event.
     */
    public CreateResult createIfAbsent(
            String publicId,
            String deliveryKey,
            NotificationMessage message,
            NotificationDestination destination,
            String requestId,
            Instant now) {
        int updated = jdbcTemplate.update(
                """
                INSERT IGNORE INTO koc_notification_delivery
                  (public_id, delivery_key, event_id, event_type, routing_key, provider_key,
                   destination_id, target_alias, operation, priority, title, summary,
                   message_json, destination_json, status, request_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SEND', ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                """,
                requireText(publicId, "publicId"),
                requireText(deliveryKey, "deliveryKey"),
                message.eventId(),
                message.eventType(),
                message.routingKey(),
                destination.providerKey(),
                destination.id(),
                destination.target(),
                message.priority().name(),
                message.title(),
                message.summary(),
                toJson(message),
                toJson(destination),
                blankToNull(requestId),
                Timestamp.from(Objects.requireNonNull(now, "now")),
                Timestamp.from(now));
        NotificationDeliveryRecord record = findByDeliveryKey(deliveryKey);
        return new CreateResult(updated == 1, record);
    }

    public NotificationDeliveryRecord findByPublicId(String publicId) {
        List<NotificationDeliveryRecord> rows = jdbcTemplate.query(
                selectColumns() + " WHERE public_id = ?", rowMapper, requireText(publicId, "publicId"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Notification delivery does not exist: " + publicId);
        }
        return rows.get(0);
    }

    public Page list(int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        int offset = (normalizedPage - 1) * normalizedSize;
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_notification_delivery", Long.class);
        List<NotificationDeliveryRecord> rows = jdbcTemplate.query(
                selectColumns() + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                rowMapper,
                normalizedSize,
                offset);
        return new Page(List.copyOf(rows), total == null ? 0 : total);
    }

    public void markProcessing(String publicId, int attempt, Instant now) {
        updateOne("""
                UPDATE koc_notification_delivery
                   SET status = 'PROCESSING', attempt = ?, updated_at = ?
                 WHERE public_id = ?
                   AND status IN ('PENDING', 'RETRYING', 'PROCESSING')
                """, Math.max(1, attempt), Timestamp.from(now), publicId);
    }

    public void markDelivered(String publicId, int attempt, NotificationDeliveryResult result, Instant now) {
        updateOne(
                """
                UPDATE koc_notification_delivery
                   SET status = 'DELIVERED',
                       attempt = ?,
                       retryable = FALSE,
                       external_message_id = ?,
                       provider_code = ?,
                       last_error_code = NULL,
                       last_error_summary = NULL,
                       delivered_at = ?,
                       updated_at = ?
                 WHERE public_id = ?
                   AND status IN ('PENDING', 'PROCESSING', 'RETRYING')
                """,
                Math.max(1, attempt),
                blankToNull(result.externalMessageId()),
                blankToNull(result.code()),
                Timestamp.from(now),
                Timestamp.from(now),
                publicId);
    }

    public void markFailed(
            String publicId,
            int attempt,
            NotificationDeliveryResult result,
            NotificationDeliveryStatus status,
            Instant now) {
        if (status != NotificationDeliveryStatus.RETRYING
                && status != NotificationDeliveryStatus.FAILED
                && status != NotificationDeliveryStatus.DEAD_LETTER) {
            throw new IllegalArgumentException("Failure status must be RETRYING, FAILED or DEAD_LETTER");
        }
        updateOne(
                """
                UPDATE koc_notification_delivery
                   SET status = ?,
                       attempt = ?,
                       retryable = ?,
                       last_error_code = ?,
                       last_error_summary = ?,
                       updated_at = ?
                 WHERE public_id = ?
                   AND status IN ('PENDING', 'PROCESSING', 'RETRYING')
                """,
                status.name(),
                Math.max(1, attempt),
                result.retryable(),
                bounded(result.code(), MAX_ERROR_CODE_LENGTH),
                bounded(result.detail(), MAX_ERROR_SUMMARY_LENGTH),
                Timestamp.from(now),
                publicId);
    }

    public boolean requeue(String publicId, NotificationDeliveryStatus expectedStatus, Instant now) {
        if (expectedStatus != NotificationDeliveryStatus.FAILED
                && expectedStatus != NotificationDeliveryStatus.DEAD_LETTER) {
            throw new IllegalArgumentException("Only FAILED or DEAD_LETTER deliveries can be replayed");
        }
        return jdbcTemplate.update("""
                        UPDATE koc_notification_delivery
                           SET status = 'PENDING',
                               attempt = 0,
                               replay_count = replay_count + 1,
                               retryable = FALSE,
                               external_message_id = NULL,
                               provider_code = NULL,
                               last_error_code = NULL,
                               last_error_summary = NULL,
                               delivered_at = NULL,
                               updated_at = ?
                         WHERE public_id = ?
                           AND status = ?
                        """, Timestamp.from(now), requireText(publicId, "publicId"), expectedStatus.name())
                == 1;
    }

    /**
     * Closes the crash window where Outbox exhausts a lease before the handler can mirror its
     * terminal state. An active manual-replay Outbox event always wins over an older dead letter.
     */
    public int reconcileDeadLetters(Instant now) {
        return jdbcTemplate.update("""
                UPDATE koc_notification_delivery delivery
                   SET status = 'DEAD_LETTER',
                       retryable = TRUE,
                       last_error_code =
                           COALESCE(delivery.last_error_code, 'OUTBOX_MAX_ATTEMPTS_EXHAUSTED'),
                       last_error_summary =
                           COALESCE(delivery.last_error_summary, 'Notification Outbox attempts were exhausted'),
                       updated_at = ?
                 WHERE delivery.status IN ('PENDING', 'PROCESSING', 'RETRYING')
                   AND EXISTS (
                       SELECT 1
                         FROM koc_outbox_event exhausted
                        WHERE exhausted.aggregate_type = 'NOTIFICATION_DELIVERY'
                          AND exhausted.aggregate_public_id = delivery.public_id
                          AND exhausted.event_type = 'notification.delivery.requested'
                          AND exhausted.status = 'DEAD_LETTER'
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM koc_outbox_event active
                        WHERE active.aggregate_type = 'NOTIFICATION_DELIVERY'
                          AND active.aggregate_public_id = delivery.public_id
                          AND active.event_type = 'notification.delivery.requested'
                          AND active.status IN ('PENDING', 'PROCESSING')
                   )
                """, Timestamp.from(Objects.requireNonNull(now, "now")));
    }

    private NotificationDeliveryRecord findByDeliveryKey(String deliveryKey) {
        List<NotificationDeliveryRecord> rows = jdbcTemplate.query(
                selectColumns() + " WHERE delivery_key = ?", rowMapper, requireText(deliveryKey, "deliveryKey"));
        if (rows.isEmpty()) {
            throw new IllegalStateException("Notification delivery was not persisted");
        }
        return rows.get(0);
    }

    private void updateOne(String sql, Object... arguments) {
        int updated = jdbcTemplate.update(sql, arguments);
        if (updated != 1) {
            throw new IllegalStateException("Notification delivery transition updated " + updated + " rows");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to serialize notification delivery", exception);
        }
    }

    private static String selectColumns() {
        return """
                SELECT id, public_id, delivery_key, operation, status, attempt, replay_count, retryable,
                       external_message_id, provider_code, last_error_code, last_error_summary,
                       request_id, message_json, destination_json, delivered_at, created_at, updated_at
                  FROM koc_notification_delivery
                """;
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

    private static String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    public record CreateResult(boolean created, NotificationDeliveryRecord record) {}

    public record Page(List<NotificationDeliveryRecord> rows, long total) {}

    private final class DeliveryRowMapper implements RowMapper<NotificationDeliveryRecord> {

        @Override
        public NotificationDeliveryRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            try {
                return new NotificationDeliveryRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("public_id"),
                        resultSet.getString("delivery_key"),
                        objectMapper.readValue(resultSet.getString("message_json"), NotificationMessage.class),
                        objectMapper.readValue(resultSet.getString("destination_json"), NotificationDestination.class),
                        resultSet.getString("operation"),
                        NotificationDeliveryStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempt"),
                        resultSet.getInt("replay_count"),
                        resultSet.getBoolean("retryable"),
                        resultSet.getString("external_message_id"),
                        resultSet.getString("provider_code"),
                        resultSet.getString("last_error_code"),
                        resultSet.getString("last_error_summary"),
                        resultSet.getString("request_id"),
                        instant(resultSet, "delivered_at"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "updated_at"));
            } catch (SQLException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new SQLException("Failed to decode notification delivery JSON", exception);
            }
        }

        private Instant instant(ResultSet resultSet, String column) throws SQLException {
            Timestamp timestamp = resultSet.getTimestamp(column);
            return timestamp == null ? null : timestamp.toInstant();
        }
    }
}
