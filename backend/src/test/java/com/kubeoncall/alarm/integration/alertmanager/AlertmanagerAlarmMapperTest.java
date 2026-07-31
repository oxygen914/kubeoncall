package com.kubeoncall.alarm.integration.alertmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.ingest.AlarmPayload;

class AlertmanagerAlarmMapperTest {

    private final AlertmanagerAlarmMapper mapper = new AlertmanagerAlarmMapper();

    @Test
    void shouldPreserveAlertmanagerIdentityAndLifecycleFields() {
        AlertmanagerAlertDto alert = new AlertmanagerAlertDto(
                "resolved",
                Map.of("alertname", "NodeNotReady", "node", "node-a", "cluster", "prod", "severity", "critical"),
                Map.of("summary", "node unavailable", "runbook_url", "https://runbook.example/node"),
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:05:00Z"),
                "https://prometheus.example/graph",
                "fp-1");
        AlertmanagerWebhookRequest webhook = new AlertmanagerWebhookRequest(
                "4",
                "group-1",
                0,
                "resolved",
                "oncall",
                Map.of(),
                Map.of("team", "platform"),
                Map.of(),
                null,
                List.of(alert));

        AlarmPayload request = mapper.map(webhook, alert);

        assertEquals("fp-1", request.fingerprint());
        assertEquals("node", request.resourceType());
        assertEquals("node-a", request.resourceName());
        assertEquals("resolved", request.status());
        assertEquals("platform", request.labels().get("team"));
        assertEquals("2026-07-15T00:05:00Z", request.metadata().get("endsAt"));
        assertEquals("alertmanager", request.metadata().get("origin"));
    }

    @Test
    void shouldConsumeFieldsEmittedByAlarmPolicyCompiler() {
        AlertmanagerAlertDto alert = new AlertmanagerAlertDto(
                "firing",
                Map.of(
                        "alertname", "KubeApiServerDownP0",
                        "cluster", "prod",
                        "severity", "P0",
                        "resource_type", "cluster",
                        "kubeoncall_metric", "kube.controlplane.apiserver_up",
                        "runbook_id", "runbook-control-plane-apiserver"),
                Map.of(
                        "summary", "API server unavailable",
                        "runbook_id", "runbook-control-plane-apiserver"),
                Instant.parse("2026-07-28T00:00:00Z"),
                null,
                null,
                "fp-compiler");
        AlertmanagerWebhookRequest webhook = new AlertmanagerWebhookRequest(
                "4", "group-compiler", 0, "firing", "oncall", Map.of(), Map.of(), Map.of(), null, List.of(alert));

        AlarmPayload request = mapper.map(webhook, alert);

        assertEquals("cluster", request.resourceType());
        assertEquals("prod", request.resourceName());
        assertEquals("kube.controlplane.apiserver_up", request.metricName());
        assertEquals("runbook-control-plane-apiserver", request.runbookId());
    }

    @Test
    void shouldPreferPodWhenAlertContainsBothPodAndNodeLabels() {
        AlertmanagerAlertDto alert = new AlertmanagerAlertDto(
                "firing",
                Map.of(
                        "alertname", "PodOOMKilledP1",
                        "cluster", "prod",
                        "namespace", "payments",
                        "node", "node-a",
                        "pod", "checkout-7b9c",
                        "severity", "P1"),
                Map.of("summary", "container was OOMKilled"),
                Instant.parse("2026-07-28T00:00:00Z"),
                null,
                null,
                "fp-pod-node");
        AlertmanagerWebhookRequest webhook = new AlertmanagerWebhookRequest(
                "4", "group-pod", 0, "firing", "oncall", Map.of(), Map.of(), Map.of(), null, List.of(alert));

        AlarmPayload request = mapper.map(webhook, alert);

        assertEquals("pod", request.resourceType());
        assertEquals("checkout-7b9c", request.resourceName());
    }
}
