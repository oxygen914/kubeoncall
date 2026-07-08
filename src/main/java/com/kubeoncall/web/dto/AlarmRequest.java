package com.kubeoncall.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Inbound alarm request.
 *
 * <p>Backwards compatible: the legacy fields ({@code alarmId}, {@code dedupKey}, {@code source},
 * {@code severity}, {@code nodeName}, {@code summary}, {@code occurredAt}, {@code metadata}) are all
 * retained and a legacy-only request still drives the full workflow. The added standard fields
 * ({@code fingerprint}, {@code alertName}, {@code resourceType}, …) let upstream systems send a
 * normalized event directly; the {@code AlarmNormalizer} reconciles both shapes into a single
 * {@link com.kubeoncall.alarm.domain.NormalizedAlarmEvent}.
 */
public record AlarmRequest(
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
        String status
) {

    /** Compact constructor: tolerate compact canonicalization for legacy callers. */
    public AlarmRequest {
        // Nothing to coerce — Jackson leaves absent fields null and the normalizer handles nulls.
    }
}
