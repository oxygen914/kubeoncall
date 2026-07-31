package com.kubeoncall.audit.outbox;

import java.time.Instant;

/**
 * An event claimed from {@code koc_outbox_event}.
 *
 * <p>{@link #eventId()} is the stable idempotency key that handlers must pass to downstream
 * systems. A handler may be invoked again when its side effect succeeds but the database lease is
 * lost before the event can be marked published.
 */
public record OutboxEvent(
        long id,
        String eventId,
        String aggregateType,
        String aggregatePublicId,
        String eventType,
        int schemaVersion,
        String payloadJson,
        String requestId,
        int attempt,
        int maxAttempts,
        Instant createdAt,
        Instant leaseUntil) {

    public String idempotencyKey() {
        return eventId;
    }
}
