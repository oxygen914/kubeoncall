package com.kubeoncall.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.web.dto.AlarmRequest;

class AlarmNormalizerTest {

    private final AlarmNormalizer normalizer = new AlarmNormalizer(new AlarmFingerprintService());

    @Test
    void shouldNormalizeLegacyRequestAndLiftNodeNameToResourceName() {
        AlarmRequest request = new AlarmRequest(
                "alarm-1",
                "dedup-1",
                "prometheus",
                "critical",
                "node-a",
                "cpu high",
                Instant.now(),
                Map.of(
                        "namespace",
                        "monitoring",
                        "service",
                        "infra-exporter",
                        "cluster",
                        "lab",
                        "alertName",
                        "HostHighCpuUsage",
                        "runbookId",
                        "runbook-host-cpu-high"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        NormalizedAlarmEvent event = normalizer.normalize(request);

        assertEquals("alarm-1", event.alarmId());
        assertEquals("node-a", event.resourceName());
        assertEquals(AlarmResourceType.NODE, event.resourceType());
        assertEquals("monitoring", event.namespace());
        assertEquals("infra-exporter", event.service());
        assertEquals("lab", event.cluster());
        assertEquals("HostHighCpuUsage", event.alertName());
        assertEquals("runbook-host-cpu-high", event.runbookId());
        assertEquals(AlarmSeverity.P0, event.severity());
        assertEquals("critical", event.rawSeverity());
        assertEquals(AlarmStatus.FIRING, event.status());
        assertNotNull(event.fingerprint());
        assertTrue(event.fingerprint().startsWith("dedup-1"), "legacy dedupKey should be reused as fingerprint");
    }

    @Test
    void shouldTakeStandardFieldsAsIs() {
        AlarmRequest request = new AlarmRequest(
                "alarm-2",
                null,
                "alertmanager",
                "warning",
                null,
                null,
                Instant.now(),
                Map.of(),
                "fp-std",
                "HostHighCpuUsageP0",
                "NODE",
                "node-a",
                "lab",
                "monitoring",
                "infra-exporter",
                "host.cpu.usage_percent",
                85.1,
                85.0,
                "%",
                "5m",
                Map.of("team", "infra", "env", "lab"),
                Map.of("summary", "cpu high"),
                "runbook-host-cpu-high",
                "FIRING");

        NormalizedAlarmEvent event = normalizer.normalize(request);

        assertEquals("fp-std", event.fingerprint(), "explicit fingerprint should be preserved");
        assertEquals("HostHighCpuUsageP0", event.alertName());
        assertEquals(AlarmResourceType.NODE, event.resourceType());
        assertEquals("node-a", event.resourceName());
        assertEquals(85.1, event.currentValue());
        assertEquals(85.0, event.threshold());
        assertEquals("%", event.unit());
        assertEquals("5m", event.duration());
        assertEquals("runbook-host-cpu-high", event.runbookId());
        assertEquals(AlarmStatus.FIRING, event.status());
        assertEquals(AlarmSeverity.P2, event.severity(), "warning maps to P2");
        assertEquals("infra", event.labels().get("team"));
    }

    @Test
    void shouldGenerateFingerprintWhenNoDedupKeyOrFingerprintProvided() {
        AlarmRequest request = new AlarmRequest(
                "alarm-3",
                null,
                "prometheus",
                "warning",
                "node-a",
                "cpu high",
                Instant.now(),
                Map.of("alertName", "HostHighCpuUsage"),
                null,
                "HostHighCpuUsage",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        NormalizedAlarmEvent event = normalizer.normalize(request);

        assertNotNull(event.fingerprint());
        assertTrue(
                event.fingerprint().startsWith("fp:"),
                "missing dedupKey/fingerprint must yield a generated fp: fingerprint");
        assertNotEqualsNullDedup(event.fingerprint());
    }

    @Test
    void shouldParseResolvedStatus() {
        AlarmRequest request = new AlarmRequest(
                "alarm-4",
                "d4",
                "prometheus",
                "info",
                "node-a",
                "recovered",
                Instant.now(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "RESOLVED");

        NormalizedAlarmEvent event = normalizer.normalize(request);
        assertEquals(AlarmStatus.RESOLVED, event.status());
    }

    @Test
    void shouldDefaultMissingFieldsGracefully() {
        AlarmRequest request = new AlarmRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);

        NormalizedAlarmEvent event = normalizer.normalize(request);
        assertNull(event.alertName());
        assertNull(event.resourceName());
        assertEquals(AlarmStatus.FIRING, event.status());
        assertNotNull(event.fingerprint());
        assertEquals("unknown", event.source());
        assertNotNull(event.occurredAt());
    }

    private static void assertNotEqualsNullDedup(String fingerprint) {
        org.junit.jupiter.api.Assertions.assertNotEquals("alarm-dedup:null", "alarm-dedup:" + fingerprint);
    }
}
