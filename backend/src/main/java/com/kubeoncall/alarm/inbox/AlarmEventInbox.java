package com.kubeoncall.alarm.inbox;

import java.time.Duration;
import java.util.List;

import com.kubeoncall.alarm.ingest.InboundAlarmEvent;

/** Reliable alarm inbox abstraction. The production implementation uses a Redis Stream. */
public interface AlarmEventInbox {

    EnqueueResult enqueue(InboundAlarmEvent event);

    List<ClaimedAlarmEvent> claim(String consumer, int batchSize, Duration block);

    boolean renew(ClaimedAlarmEvent event);

    void acknowledge(ClaimedAlarmEvent event);

    void retry(ClaimedAlarmEvent event, String reason);

    void deadLetter(ClaimedAlarmEvent event, String reason);

    record EnqueueResult(boolean accepted, boolean duplicate, String eventId) {}

    record ClaimedAlarmEvent(String recordId, InboundAlarmEvent event, String consumer) {

        public ClaimedAlarmEvent(String recordId, InboundAlarmEvent event) {
            this(recordId, event, "");
        }
    }
}
