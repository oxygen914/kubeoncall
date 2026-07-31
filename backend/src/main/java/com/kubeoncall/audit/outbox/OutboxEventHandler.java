package com.kubeoncall.audit.outbox;

/**
 * Performs the external side effect for one outbox event type.
 *
 * <p>Implementations must make the side effect idempotent with {@link OutboxEvent#eventId()}.
 */
public interface OutboxEventHandler {

    String eventType();

    void handle(OutboxEvent event) throws Exception;
}
