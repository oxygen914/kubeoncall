package com.kubeoncall.web.dto;

import java.time.Instant;
import java.util.Map;

public record AlarmRequest(
        String alarmId,
        String dedupKey,
        String source,
        String severity,
        String nodeName,
        String summary,
        Instant occurredAt,
        Map<String, Object> metadata
) {
}
