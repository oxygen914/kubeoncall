package com.kubeoncall.alarm.correlation;

import java.time.Instant;
import java.util.Map;

/** An auditable deployment or infrastructure mutation that may explain a subsequent alarm. */
public record ChangeEvent(
        String changeId,
        String changeType,
        String changedBy,
        Instant changedAt,
        String resourceType,
        String resourceName,
        String namespace,
        String cluster,
        Map<String, Object> diff,
        String changeSource,
        String correlationId) {

    public ChangeEvent {
        changedAt = changedAt == null ? Instant.now() : changedAt;
        diff = diff == null ? Map.of() : Map.copyOf(diff);
    }
}
