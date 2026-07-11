package com.kubeoncall.alarm.maintenance;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlarmMaintenanceWindowServiceTest {

    @Test
    void shouldRequireIndependentApproval() {
        AlarmMaintenanceWindowService service = new AlarmMaintenanceWindowService(mock(AlarmMaintenanceWindowStore.class));
        Instant now = Instant.now();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                now, now.plus(1, ChronoUnit.HOURS), Map.of("service", "payment-*"),
                "release", "operator-a", "operator-a", "change-123"));

        assertTrue(error.getMessage().contains("must be different"));
    }

    @Test
    void shouldPersistApprovedWindowAndMatchAllSelectors() {
        AlarmMaintenanceWindowStore store = mock(AlarmMaintenanceWindowStore.class);
        AlarmMaintenanceWindowService service = new AlarmMaintenanceWindowService(store);
        Instant now = Instant.now();

        AlarmMaintenanceWindow window = service.create(
                now.minusSeconds(10), now.plusSeconds(600),
                Map.of("cluster", "prod-*", "service", "payment-*", "resourceType", "pod", "label.team", "payments"),
                "payment release", "operator-a", "approver-b", "change-123");

        verify(store).save(window);
        assertTrue(service.matches(window, event()));
    }

    @Test
    void shouldNotMatchWhenAnySelectorDiffers() {
        AlarmMaintenanceWindowService service = new AlarmMaintenanceWindowService(mock(AlarmMaintenanceWindowStore.class));
        Instant now = Instant.now();
        AlarmMaintenanceWindow window = new AlarmMaintenanceWindow(
                "mw-1", now.minusSeconds(10), now.plusSeconds(60),
                Map.of("service", "orders-*", "label.team", "payments"),
                "release", "operator-a", "approver-b", "change-123", now.minusSeconds(30));

        assertFalse(service.matches(window, event()));
    }

    @Test
    void shouldFailOpenWhenRedisLookupFails() {
        AlarmMaintenanceWindowStore store = mock(AlarmMaintenanceWindowStore.class);
        AlarmMaintenanceWindowService service = new AlarmMaintenanceWindowService(store);
        Instant now = Instant.now();
        when(store.activeAt(now)).thenThrow(new IllegalStateException("redis unavailable"));

        assertEquals(List.of(), service.matchingWindow(event(), now).stream().toList());
    }

    private NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "alarm-1", "fp-1", "PodCrashLoop", "prometheus", "warning", AlarmSeverity.P1,
                AlarmResourceType.POD, "payment-api-123", "prod-cn", "payments", "payment-api",
                "restart_count", 5.0, 3.0, "count", "5m", Map.of("team", "payments"), Map.of(),
                "runbook-pod", AlarmStatus.FIRING, Instant.now(), "pod restarting", Map.of());
    }
}
