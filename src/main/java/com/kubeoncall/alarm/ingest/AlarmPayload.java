package com.kubeoncall.alarm.ingest;

import java.time.Instant;
import java.util.Map;

import com.kubeoncall.alarm.domain.AlarmIngress;

/** Serializable, boundary-neutral alarm payload stored by the reliable inbox. */
public record AlarmPayload(
        String alarmId,
        String dedupKey,
        String source,
        String severity,
        String nodeName,
        String summary,
        Instant occurredAt,
        Map<String, Object> metadata,
        String fingerprint,
        String alertName,
        String resourceType,
        String resourceName,
        String cluster,
        String namespace,
        String service,
        String metricName,
        Double currentValue,
        Double threshold,
        String unit,
        String duration,
        Map<String, String> labels,
        Map<String, String> annotations,
        String runbookId,
        String status)
        implements AlarmIngress {

    public AlarmPayload {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }
}
