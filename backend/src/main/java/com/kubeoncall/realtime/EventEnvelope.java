package com.kubeoncall.realtime;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Stable public event contract delivered over the realtime stream.
 *
 * <p>The event id is the durable outbox idempotency key. Consumers may persist it and send it back
 * as {@code Last-Event-ID} to resume within the broker's short replay window.
 */
public record EventEnvelope(
        String eventId,
        String topic,
        String type,
        String resourceId,
        Instant occurredAt,
        JsonNode payload,
        int schemaVersion) {

    public EventEnvelope {
        eventId = requireText(eventId, "eventId");
        topic = requireText(topic, "topic");
        type = requireText(type, "type");
        resourceId = requireText(resourceId, "resourceId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = payload == null ? NullNode.getInstance() : payload.deepCopy();
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
