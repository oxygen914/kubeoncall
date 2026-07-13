package com.kubeoncall.alarm.domain;

import java.time.Instant;
import java.util.Map;

/**
 * The platform's standard alarm event.
 *
 * <p>This is the single normalized shape that downstream components (policy engine, dedup,
 * suppression, workflow, memory, RAG) operate on. It preserves the upstream {@code rawSeverity}
 * alongside the platform-computed {@code severity}, and carries enough structured context
 * ({@code alertName}, {@code resourceType}, {@code metricName}, {@code currentValue}…) to drive
 * policy matching and runbook filtering without re-parsing free-text summaries.
 *
 * <p>All collection fields are guaranteed non-null by the normalizer; callers can treat them as
 * empty rather than null.
 */
public record NormalizedAlarmEvent(
        String alarmId,
        String fingerprint,
        String alertName,
        String source,
        String rawSeverity,
        AlarmSeverity severity,
        AlarmResourceType resourceType,
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
        AlarmStatus status,
        Instant occurredAt,
        String summary,
        Map<String, Object> metadata) {

    public NormalizedAlarmEvent {
        if (labels == null) {
            labels = Map.of();
        }
        if (annotations == null) {
            annotations = Map.of();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /**
     * Convenience accessor used by the dedup layer: the fingerprint is the canonical de-duplication
     * key. Callers should never fall back to {@code "alarm-dedup:null"}; when the fingerprint is
     * absent the {@code AlarmFingerprintService} must have already synthesized one.
     */
    public String dedupKey() {
        return fingerprint;
    }
}
