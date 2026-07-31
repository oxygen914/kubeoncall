package com.kubeoncall.web.api.v1.events;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kubeoncall.realtime.EventEnvelope;
import com.kubeoncall.realtime.RealtimeEventBroker;

/** Small adapter around Spring's emitter that keeps wire event names stable and testable. */
public class EventStreamEmitter {

    private final SseEmitter emitter;

    public EventStreamEmitter(long timeoutMillis) {
        this(new SseEmitter(timeoutMillis));
    }

    EventStreamEmitter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public SseEmitter emitter() {
        return emitter;
    }

    public void send(EventEnvelope event) throws IOException {
        emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
    }

    public void heartbeat() throws IOException {
        emitter.send(SseEmitter.event()
                .name("heartbeat")
                .data(Map.of("occurredAt", Instant.now().toString())));
    }

    public void cursorGap(RealtimeEventBroker.CursorGap gap) throws IOException {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("cursorExpired", gap.cursorExpired());
        payload.put("requestedEventId", gap.requestedEventId());
        putNullable(payload, "oldestAvailableEventId", gap.oldestAvailableEventId());
        putNullable(payload, "latestAvailableEventId", gap.latestAvailableEventId());
        EventEnvelope envelope = new EventEnvelope(
                "gap_" + gap.requestedEventId(),
                "system",
                "cursor.expired",
                gap.requestedEventId(),
                Instant.now(),
                payload,
                1);
        emitter.send(SseEmitter.event().id(envelope.eventId()).name("gap").data(envelope));
    }

    public void complete() {
        emitter.complete();
    }

    public void completeWithError(Throwable error) {
        emitter.completeWithError(error);
    }

    public void onClose(Runnable callback) {
        AtomicBoolean invoked = new AtomicBoolean();
        Runnable once = () -> {
            if (invoked.compareAndSet(false, true)) {
                callback.run();
            }
        };
        emitter.onCompletion(once);
        emitter.onTimeout(once);
        emitter.onError(error -> once.run());
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
