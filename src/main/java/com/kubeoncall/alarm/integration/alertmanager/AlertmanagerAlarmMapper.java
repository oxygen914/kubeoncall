package com.kubeoncall.alarm.integration.alertmanager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.ingest.AlarmPayload;

/** Maps Alertmanager's transport payload into the platform's boundary-neutral alarm request. */
@Service
public class AlertmanagerAlarmMapper {

    public AlarmPayload map(AlertmanagerWebhookRequest webhook, AlertmanagerAlertDto alert) {
        Map<String, String> labels = merge(webhook.commonLabels(), alert.labels());
        Map<String, String> annotations = merge(webhook.commonAnnotations(), alert.annotations());
        String resourceName =
                firstNonBlank(labels.get("node"), labels.get("pod"), labels.get("service"), labels.get("instance"));
        String resourceType = resourceType(labels, resourceName);
        String status = firstNonBlank(alert.status(), webhook.status(), "firing");
        Instant occurredAt = alert.startsAt() == null ? Instant.now() : alert.startsAt();
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "alertmanagerGroupKey", webhook.groupKey());
        putIfPresent(metadata, "alertmanagerReceiver", webhook.receiver());
        putIfPresent(metadata, "alertmanagerExternalUrl", webhook.externalURL());
        putIfPresent(metadata, "generatorUrl", alert.generatorURL());
        if (alert.endsAt() != null) {
            metadata.put("endsAt", alert.endsAt().toString());
        }
        metadata.put("origin", "alertmanager");

        return new AlarmPayload(
                alert.fingerprint(),
                alert.fingerprint(),
                "alertmanager",
                labels.get("severity"),
                labels.get("node"),
                firstNonBlank(annotations.get("summary"), annotations.get("description")),
                occurredAt,
                Map.copyOf(metadata),
                alert.fingerprint(),
                labels.get("alertname"),
                resourceType,
                resourceName,
                firstNonBlank(labels.get("cluster"), labels.get("kubernetes_cluster")),
                labels.get("namespace"),
                firstNonBlank(labels.get("service"), labels.get("job")),
                firstNonBlank(labels.get("metric"), labels.get("__name__")),
                null,
                null,
                null,
                labels.get("for"),
                labels,
                annotations,
                firstNonBlank(annotations.get("runbook_url"), annotations.get("runbook")),
                status);
    }

    private Map<String, String> merge(Map<String, String> common, Map<String, String> specific) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (common != null) {
            result.putAll(common);
        }
        if (specific != null) {
            result.putAll(specific);
        }
        return Map.copyOf(result);
    }

    private String resourceType(Map<String, String> labels, String resourceName) {
        if (resourceName == null) {
            return null;
        }
        if (notBlank(labels.get("node"))) {
            return "node";
        }
        if (notBlank(labels.get("pod"))) {
            return "pod";
        }
        if (notBlank(labels.get("namespace"))) {
            return "namespace";
        }
        if (notBlank(labels.get("service"))) {
            return "service";
        }
        if (notBlank(labels.get("instance"))) {
            return "host";
        }
        return "workload";
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (notBlank(value)) {
            target.put(key, value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
