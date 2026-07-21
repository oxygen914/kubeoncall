package com.kubeoncall.audit.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class OutboxEventHandlerRegistryTest {

    @Test
    void indexesHandlerByExactEventType() {
        StubHandler handler = new StubHandler("alarm.acknowledged");
        OutboxEventHandlerRegistry registry = new OutboxEventHandlerRegistry(List.of(handler));

        assertEquals(1, registry.size());
        assertSame(handler, registry.find("alarm.acknowledged").orElseThrow());
    }

    @Test
    void rejectsDuplicateEventType() {
        StubHandler first = new StubHandler("alarm.acknowledged");
        StubHandler second = new StubHandler("alarm.acknowledged");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> new OutboxEventHandlerRegistry(List.of(first, second)));

        assertEquals("Duplicate outbox handler for event type: alarm.acknowledged", exception.getMessage());
    }

    private record StubHandler(String eventType) implements OutboxEventHandler {

        @Override
        public void handle(OutboxEvent event) {}
    }
}
