package com.kubeoncall.alarm.readmodel;

import java.time.Instant;
import java.util.Map;

/** One entry in an alarm's timeline. {@code type} follows the stable timeline-type vocabulary
 * defined by the API design (ALARM_FIRING, ALARM_ACKNOWLEDGED, ALARM_RESOLVED, …). */
public record AlarmTimelineItem(
        String id,
        String type,
        Instant occurredAt,
        Actor actor,
        String summary,
        Map<String, Object> details,
        String requestId) {

    public record Actor(String type, String id, String displayName) {}
}
