package com.kubeoncall.alarm.ingest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmIngress;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;

/**
 * Reconciles the various inbound alarm shapes into one {@link NormalizedAlarmEvent}.
 *
 * <p>Two shapes are supported:
 * <ul>
 *   <li><b>Legacy</b> — only {@code nodeName/summary/severity/dedupKey} (+ metadata). The node name
 *       is lifted to {@code resourceName}, common fields are promoted from {@code metadata}, and the
 *       raw severity is parsed into a platform severity.</li>
 *   <li><b>Standard</b> — carries the structured fields directly; they are taken as-is.</li>
 * </ul>
 *
 * <p>The fingerprint is resolved last via {@link AlarmFingerprintService} so it is always present
 * on the normalized event.
 */
@Service
public class AlarmNormalizer {

    private final AlarmFingerprintService fingerprintService;

    public AlarmNormalizer(AlarmFingerprintService fingerprintService) {
        this.fingerprintService = fingerprintService;
    }

    public NormalizedAlarmEvent normalize(AlarmIngress request) {
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();

        String alertName = firstNonBlank(request.alertName(), stringFrom(metadata, "alertName"));
        String resourceTypeRaw = firstNonBlank(request.resourceType(), stringFrom(metadata, "resourceType"));
        AlarmResourceType resourceType = resourceTypeRaw != null ? AlarmResourceType.fromRaw(resourceTypeRaw) : null;
        if (resourceType == null && request.nodeName() != null) {
            // Legacy host/node alarm without an explicit resource type.
            resourceType = AlarmResourceType.NODE;
        }

        String resourceName = firstNonBlank(
                request.resourceName(),
                request.nodeName(),
                stringFrom(metadata, "nodeName"),
                stringFrom(metadata, "resourceName"));
        String cluster = firstNonBlank(request.cluster(), stringFrom(metadata, "cluster"));
        String namespace = firstNonBlank(request.namespace(), stringFrom(metadata, "namespace"));
        String service = firstNonBlank(request.service(), stringFrom(metadata, "service"));
        String metricName = firstNonBlank(request.metricName(), stringFrom(metadata, "metricName"));
        Double currentValue =
                request.currentValue() != null ? request.currentValue() : doubleFrom(metadata, "currentValue");
        Double threshold = request.threshold() != null ? request.threshold() : doubleFrom(metadata, "threshold");
        String unit = firstNonBlank(request.unit(), stringFrom(metadata, "unit"));
        String duration = firstNonBlank(request.duration(), stringFrom(metadata, "duration"));
        String runbookId = firstNonBlank(request.runbookId(), stringFrom(metadata, "runbookId"));
        String rawSeverity = firstNonBlank(request.severity(), stringFrom(metadata, "severity"));
        AlarmSeverity severity = AlarmSeverity.fromRaw(rawSeverity);

        Map<String, String> labels = mergeLabels(request.labels(), metadata);
        Map<String, String> annotations = request.annotations() == null ? Map.of() : request.annotations();

        AlarmStatus status = parseStatus(request.status());
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();
        String source = firstNonBlank(request.source(), stringFrom(metadata, "source"), "unknown");
        String summary = firstNonBlank(request.summary(), stringFrom(metadata, "summary"));
        String alarmId = firstNonBlank(request.alarmId(), stringFrom(metadata, "alarmId"));

        String fingerprint = fingerprintService.fingerprint(
                request.fingerprint(),
                request.dedupKey(),
                alertName,
                cluster,
                namespace,
                resourceType == null ? null : resourceType.name(),
                resourceName,
                service,
                metricName,
                labels);

        return new NormalizedAlarmEvent(
                alarmId,
                fingerprint,
                alertName,
                source,
                rawSeverity,
                severity,
                resourceType,
                resourceName,
                cluster,
                namespace,
                service,
                metricName,
                currentValue,
                threshold,
                unit,
                duration,
                labels,
                annotations,
                runbookId,
                status,
                occurredAt,
                summary,
                metadata);
    }

    private static Map<String, String> mergeLabels(Map<String, String> requestLabels, Map<String, Object> metadata) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (requestLabels != null) {
            merged.putAll(requestLabels);
        }
        Object metaLabels = metadata.get("labels");
        if (metaLabels instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    merged.putIfAbsent(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return Map.copyOf(merged);
    }

    private static AlarmStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return AlarmStatus.FIRING;
        }
        return switch (status.trim().toUpperCase()) {
            case "RESOLVED" -> AlarmStatus.RESOLVED;
            case "SUPPRESSED" -> AlarmStatus.SUPPRESSED;
            default -> AlarmStatus.FIRING;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String stringFrom(Map<String, Object> metadata, String key) {
        Object v = metadata.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Double doubleFrom(Map<String, Object> metadata, String key) {
        Object v = metadata.get(key);
        if (v instanceof Number number) {
            return number.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
