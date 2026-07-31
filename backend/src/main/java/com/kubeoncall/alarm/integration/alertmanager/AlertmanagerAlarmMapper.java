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
        String resourceType = resourceType(labels);
        String resourceName = resourceName(labels, resourceType);
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
                firstNonBlank(labels.get("kubeoncall_metric"), labels.get("metric"), labels.get("__name__")),
                null,
                null,
                null,
                labels.get("for"),
                labels,
                annotations,
                firstNonBlank(
                        annotations.get("runbook_id"),
                        labels.get("runbook_id"),
                        annotations.get("runbook_url"),
                        annotations.get("runbook")),
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

    private String resourceType(Map<String, String> labels) {
        String declared = labels.get("resource_type");
        if (notBlank(declared)) {
            return declared;
        }
        if (notBlank(labels.get("pod"))) {
            return "pod";
        }
        if (notBlank(labels.get("deployment"))) {
            return "deployment";
        }
        if (notBlank(labels.get("statefulset"))) {
            return "statefulset";
        }
        if (notBlank(labels.get("daemonset"))) {
            return "daemonset";
        }
        if (notBlank(labels.get("service"))) {
            return "service";
        }
        if (notBlank(labels.get("node"))) {
            return "node";
        }
        if (notBlank(labels.get("namespace"))) {
            return "namespace";
        }
        if (notBlank(labels.get("instance"))) {
            return "host";
        }
        if (notBlank(firstNonBlank(labels.get("cluster"), labels.get("kubernetes_cluster")))) {
            return "cluster";
        }
        return null;
    }

    private String resourceName(Map<String, String> labels, String resourceType) {
        if (resourceType == null) {
            return null;
        }
        return switch (resourceType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "pod" -> labels.get("pod");
            case "deployment" -> labels.get("deployment");
            case "statefulset" -> labels.get("statefulset");
            case "daemonset" -> labels.get("daemonset");
            case "service" -> labels.get("service");
            case "node" -> firstNonBlank(labels.get("node"), labels.get("instance"));
            case "namespace" -> labels.get("namespace");
            case "host" -> firstNonBlank(labels.get("instance"), labels.get("node"));
            case "cluster" -> firstNonBlank(labels.get("cluster"), labels.get("kubernetes_cluster"));
            default ->
                firstNonBlank(
                        labels.get("pod"),
                        labels.get("deployment"),
                        labels.get("statefulset"),
                        labels.get("daemonset"),
                        labels.get("service"),
                        labels.get("node"),
                        labels.get("instance"),
                        labels.get("cluster"),
                        labels.get("kubernetes_cluster"));
        };
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
