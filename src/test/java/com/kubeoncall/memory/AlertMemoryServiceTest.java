package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

class AlertMemoryServiceTest {

    @Test
    void shouldRecallByFingerprintServiceAndResource() {
        MemoryService memoryService = mock(MemoryService.class);
        AlertMemoryService service = new AlertMemoryService(memoryService, new KubeOnCallProperties());
        MemoryEntry byFingerprint = memory("m1");
        MemoryEntry byService = memory("m2");
        when(memoryService.search(anyString(), eq(Map.of("fingerprint", "fp-1")), anyInt()))
                .thenReturn(List.of(byFingerprint));
        when(memoryService.search(anyString(), eq(Map.of("service", "payment-service")), anyInt()))
                .thenReturn(List.of(byService));
        when(memoryService.search(anyString(), eq(Map.of("resource", "payment-pod")), anyInt()))
                .thenReturn(List.of(byFingerprint));

        List<MemoryEntry> recalled = service.recall(event());

        assertEquals(List.of("m1", "m2"), recalled.stream().map(MemoryEntry::id).toList());
    }

    @Test
    void shouldRememberAlarmResolution() {
        MemoryService memoryService = mock(MemoryService.class);
        AlertMemoryService service = new AlertMemoryService(memoryService, new KubeOnCallProperties());

        service.rememberResolution(event(), "reduced memory limit spike");

        verify(memoryService)
                .remember(org.mockito.ArgumentMatchers.argThat(entry -> entry.type() == MemoryType.INCIDENT_SUMMARY
                        && "fp-1".equals(entry.fingerprint())
                        && "payment-service".equals(entry.service())));
    }

    private MemoryEntry memory(String id) {
        return new MemoryEntry(
                id,
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.FINGERPRINT,
                "alert",
                "history",
                "payment-service",
                "payment-pod",
                "fp-1",
                Instant.now(),
                Instant.now(),
                Map.of());
    }

    private NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fp-1",
                "PodOOMKilled",
                "prometheus",
                "critical",
                AlarmSeverity.P1,
                AlarmResourceType.POD,
                "payment-pod",
                "prod-a",
                "prod",
                "payment-service",
                "container_memory_working_set_bytes",
                2.0,
                1.0,
                "GiB",
                "5m",
                Map.of(),
                Map.of(),
                "runbook-oom",
                AlarmStatus.FIRING,
                Instant.now(),
                "oom",
                Map.of());
    }
}
