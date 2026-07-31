package com.kubeoncall.realtime;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.kubeoncall.audit.outbox.OutboxEvent;
import com.kubeoncall.audit.outbox.OutboxEventHandler;

/** Converts one durable outbox event type into the public realtime envelope. */
public final class RealtimeOutboxEventHandler implements OutboxEventHandler {

    private final String eventType;
    private final ObjectMapper objectMapper;
    private final RealtimeEventHub eventHub;

    public RealtimeOutboxEventHandler(String eventType, ObjectMapper objectMapper, RealtimeEventHub eventHub) {
        this.eventType = requireText(eventType, "eventType");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventHub = Objects.requireNonNull(eventHub, "eventHub");
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        if (!eventType.equals(event.eventType())) {
            throw new IllegalArgumentException("Handler for " + eventType + " cannot process " + event.eventType());
        }
        EventTopic topic = EventTopic.fromAggregate(event.aggregateType(), event.eventType());
        eventHub.publish(new EventEnvelope(
                event.eventId(),
                topic.wireName(),
                event.eventType(),
                event.aggregatePublicId(),
                event.createdAt(),
                payload(event.payloadJson()),
                event.schemaVersion()));
    }

    private JsonNode payload(String payloadJson) throws Exception {
        return payloadJson == null || payloadJson.isBlank()
                ? NullNode.getInstance()
                : objectMapper.readTree(payloadJson);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
