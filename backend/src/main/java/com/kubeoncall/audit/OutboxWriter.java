package com.kubeoncall.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Enqueues {@code koc_outbox_event} rows for reliable post-commit side effects (notifications, SSE,
 * cache invalidation). Inserted inside the business transaction so it commits atomically with the
 * state change; a separate worker (WBS-7) drains the outbox. The {@code event_id} is globally unique
 * and is the consumer's idempotency key, so a redelivered event never produces a duplicate side effect.
 */
@Service
public class OutboxWriter {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;

    public OutboxWriter(ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return jdbcTemplateProvider.getIfAvailable() != null;
    }

    public void enqueue(OutboxEvent event) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Outbox is unavailable");
        }
        jdbcTemplate.update(
                """
                    INSERT INTO koc_outbox_event
                      (event_id, aggregate_type, aggregate_public_id, event_type, schema_version,
                       payload_json, request_id, status, max_attempts, next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """,
                event.eventId(),
                event.aggregateType(),
                event.aggregatePublicId(),
                event.eventType(),
                event.schemaVersion(),
                toJson(event.payload()),
                event.requestId(),
                event.maxAttempts(),
                Timestamp.from(Instant.now()));
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize outbox payload", ex);
        }
    }

    public record OutboxEvent(
            String eventId,
            String aggregateType,
            String aggregatePublicId,
            String eventType,
            int schemaVersion,
            Map<String, Object> payload,
            String requestId,
            int maxAttempts) {

        public static OutboxEvent of(
                String aggregateType,
                String aggregatePublicId,
                String eventType,
                Map<String, Object> payload,
                String requestId) {
            return new OutboxEvent(
                    "oevt_" + UUID.randomUUID().toString().replace("-", ""),
                    aggregateType,
                    aggregatePublicId,
                    eventType,
                    1,
                    payload == null ? Map.of() : new LinkedHashMap<>(payload),
                    requestId == null ? "" : requestId,
                    10);
        }
    }
}
