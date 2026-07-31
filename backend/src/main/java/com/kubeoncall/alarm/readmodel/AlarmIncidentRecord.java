package com.kubeoncall.alarm.readmodel;

import java.time.Instant;
import java.util.Map;

/**
 * Read-optimised view of a {@code koc_alarm_incident} row. Mirrors the list/detail contract fields
 * the {@code /api/v1/alarms} surface returns. {@code labels} and {@code annotations} are decoded
 * from JSON by the repository so callers never touch raw JSON columns.
 */
public record AlarmIncidentRecord(
        long id,
        String publicId,
        String fingerprint,
        int cycleNo,
        String alertName,
        String severity,
        int severityRank,
        String status,
        String resourceType,
        String resourceName,
        String cluster,
        String namespace,
        String service,
        String metricName,
        Double currentValue,
        Double threshold,
        String unit,
        Map<String, String> labels,
        Map<String, String> annotations,
        Instant firstSeen,
        Instant lastSeen,
        Instant resolvedAt,
        long occurrenceCount,
        boolean acknowledged,
        Long acknowledgedBy,
        Instant acknowledgedAt,
        String policyPublicId,
        String latestExecutionPublicId,
        String latestExecutionStatus,
        long version) {}
