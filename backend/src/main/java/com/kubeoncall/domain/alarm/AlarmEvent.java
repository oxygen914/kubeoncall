package com.kubeoncall.domain.alarm;

import java.time.Instant;
import java.util.Map;

public record AlarmEvent(
        String alarmId,
        String dedupKey,
        String source,
        String severity,
        String nodeName,
        String summary,
        Instant occurredAt,
        Map<String, Object> metadata) {}
