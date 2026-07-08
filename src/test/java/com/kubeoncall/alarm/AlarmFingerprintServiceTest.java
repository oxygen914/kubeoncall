package com.kubeoncall.alarm;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmFingerprintServiceTest {

    private final AlarmFingerprintService service = new AlarmFingerprintService();

    @Test
    void shouldPreferExplicitFingerprint() {
        String fp = service.fingerprint("fp-explicit", null, "Alert", "c", "ns", "NODE", "n", "svc", "m", Map.of());
        assertEquals("fp-explicit", fp);
    }

    @Test
    void shouldFallBackToDedupKeyWhenNoFingerprint() {
        String fp = service.fingerprint(null, "dedup-abc", "Alert", "c", "ns", "NODE", "n", "svc", "m", Map.of());
        assertEquals("dedup-abc", fp);
    }

    @Test
    void shouldGenerateStableSha256FingerprintWhenNothingProvided() {
        String fp1 = service.fingerprint(null, null, "HostHighCpuUsage", "lab", "monitoring", "NODE", "node-a", "infra-exporter", "host.cpu.usage_percent", Map.of("team", "infra"));
        String fp2 = service.fingerprint(null, null, "HostHighCpuUsage", "lab", "monitoring", "NODE", "node-a", "infra-exporter", "host.cpu.usage_percent", Map.of("team", "infra"));
        assertNotNull(fp1);
        assertTrue(fp1.startsWith("fp:"), "generated fingerprint should be prefixed with fp:");
        assertEquals(fp1, fp2, "same inputs must produce the same fingerprint");
    }

    @Test
    void shouldDifferWhenStableLabelChanges() {
        String fp1 = service.fingerprint(null, null, "Alert", "c", "ns", "NODE", "n", "svc", "m", Map.of("team", "infra"));
        String fp2 = service.fingerprint(null, null, "Alert", "c", "ns", "NODE", "n", "svc", "m", Map.of("team", "app"));
        assertNotEquals(fp1, fp2);
    }

    @Test
    void shouldIgnoreTransientLabels() {
        // pod-template-hash is transient; it must not fragment the fingerprint.
        String fp1 = service.fingerprint(null, null, "Alert", "c", "ns", "NODE", "n", "svc", "m", Map.of("team", "infra", "pod-template-hash", "abc"));
        String fp2 = service.fingerprint(null, null, "Alert", "c", "ns", "NODE", "n", "svc", "m", Map.of("team", "infra", "pod-template-hash", "xyz"));
        assertEquals(fp1, fp2);
    }

    @Test
    void shouldNeverProduceNullDedupKeyFromEvent() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id", null, "Alert", "prom", "warning", null,
                AlarmResourceType.NODE, "node-a", "lab", "monitoring", "svc", "m",
                80.0, 70.0, "%", "5m",
                Map.of("team", "infra"), Map.of(), "rb", null, Instant.now(), "s", Map.of());
        String fp = service.fingerprint(event);
        assertNotNull(fp);
        assertFalse(fp.isBlank());
        assertTrue(fp.startsWith("fp:"));
    }
}
