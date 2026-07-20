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
}
