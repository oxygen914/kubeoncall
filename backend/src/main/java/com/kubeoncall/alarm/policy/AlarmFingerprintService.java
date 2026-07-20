package com.kubeoncall.alarm.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

/**
 * Computes a stable fingerprint for a normalized alarm event.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Explicit {@code fingerprint} already set on the event.</li>
 *   <li>Explicit upstream {@code dedupKey} (carried in metadata under {@code dedupKey}).</li>
 *   <li>SHA-256 over the stable tuple
 *       {@code alertName + cluster + namespace + resourceType + resourceName + service + metricName + sorted stable labels}.
 * </ol>
 *
 * <p>This guarantees that alarms without a {@code dedupKey} never produce a {@code alarm-dedup:null}
 * key, and that the same logical alarm yields the same fingerprint across retries.
 */
@Service
public class AlarmFingerprintService {

    /** Labels that participate in fingerprinting; transient/runtime labels are excluded. */
    private static final java.util.Set<String> STABLE_LABELS =
            java.util.Set.of("team", "env", "tier", "instance", "fstype", "mountpoint", "severity_hint");

    /**
     * Resolve a fingerprint for the given event fields. If an explicit {@code existing} fingerprint
     * or {@code dedupKey} is present, it is returned unchanged.
     */
    public String fingerprint(
            String existingFingerprint,
            String dedupKey,
            String alertName,
            String cluster,
            String namespace,
            String resourceType,
            String resourceName,
            String service,
            String metricName,
            Map<String, String> labels) {
        if (existingFingerprint != null && !existingFingerprint.isBlank()) {
            return existingFingerprint;
        }
        if (dedupKey != null && !dedupKey.isBlank()) {
            return dedupKey;
        }
        return sha256(buildFingerprintSource(
                alertName, cluster, namespace, resourceType, resourceName, service, metricName, labels));
    }

    /** Resolve the fingerprint for an already-normalized event. */
    public String fingerprint(NormalizedAlarmEvent event) {
        return fingerprint(
                event.fingerprint(),
                dedupKeyFromMetadata(event),
                event.alertName(),
                event.cluster(),
                event.namespace(),
                event.resourceType() == null ? null : event.resourceType().name(),
                event.resourceName(),
                event.service(),
                event.metricName(),
                event.labels());
    }

    private static String dedupKeyFromMetadata(NormalizedAlarmEvent event) {
        Object raw = event.metadata().get("dedupKey");
        return raw == null ? null : String.valueOf(raw);
    }

    private static String buildFingerprintSource(
            String alertName,
            String cluster,
            String namespace,
            String resourceType,
            String resourceName,
            String service,
            String metricName,
            Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        append(sb, "alertName", alertName);
        append(sb, "cluster", cluster);
        append(sb, "namespace", namespace);
        append(sb, "resourceType", resourceType);
        append(sb, "resourceName", resourceName);
        append(sb, "service", service);
        append(sb, "metricName", metricName);
        // Sorted stable labels only — transient labels must not fragment the fingerprint.
        TreeMap<String, String> stable = new TreeMap<>();
        if (labels != null) {
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (STABLE_LABELS.contains(e.getKey()) && e.getValue() != null) {
                    stable.put(e.getKey(), e.getValue());
                }
            }
        }
        for (Map.Entry<String, String> e : stable.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value == null ? "" : value).append(';');
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return "fp:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JVM spec; this should never happen.
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
