package com.kubeoncall.alarm.ingest;

import java.time.Instant;

/** A durably accepted inbound alarm, ready for asynchronous workflow processing. */
public record InboundAlarmEvent(
        String eventId,
        String batchId,
        String deliveryKey,
        String origin,
        Instant receivedAt,
        int attempt,
        AlarmPayload alarm) {

    public InboundAlarmEvent {
        origin = origin == null || origin.isBlank() ? "unknown" : origin;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        attempt = Math.max(0, attempt);
    }

    public InboundAlarmEvent nextAttempt() {
        return new InboundAlarmEvent(eventId, batchId, deliveryKey, origin, receivedAt, attempt + 1, alarm);
    }
}
