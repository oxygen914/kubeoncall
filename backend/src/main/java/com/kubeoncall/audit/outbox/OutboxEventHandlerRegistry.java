package com.kubeoncall.audit.outbox;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable event-type registry. Duplicate handler registrations fail fast. */
public final class OutboxEventHandlerRegistry {

    private final Map<String, OutboxEventHandler> handlers;

    public OutboxEventHandlerRegistry(Collection<? extends OutboxEventHandler> handlers) {
        Map<String, OutboxEventHandler> indexed = new LinkedHashMap<>();
        if (handlers != null) {
            for (OutboxEventHandler handler : handlers) {
                if (handler == null) {
                    throw new IllegalArgumentException("Outbox handler must not be null");
                }
                String eventType = requireText(handler.eventType(), "handler eventType");
                OutboxEventHandler previous = indexed.putIfAbsent(eventType, handler);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate outbox handler for event type: " + eventType);
                }
            }
        }
        this.handlers = Map.copyOf(indexed);
    }

    public Optional<OutboxEventHandler> find(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(eventType.trim()));
    }

    public int size() {
        return handlers.size();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
