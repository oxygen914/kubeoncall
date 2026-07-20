package com.kubeoncall.alarm.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

class ChangeCorrelationServiceTest {

    @Test
    void shouldRankRecentSameResourceImageChange() {
        ChangeCorrelationService service = new ChangeCorrelationService(new InMemoryChangeEventRepository());
        Instant occurredAt = Instant.parse("2026-07-15T00:30:00Z");
        service.record(new ChangeEvent(
                "change-1",
                "deployment_image_change",
                "pipeline",
                occurredAt.minusSeconds(120),
                "deployment",
                "payment-api",
                "payments",
                "prod",
                Map.of(),
                "ci-cd",
                "build-1"));

        var results = service.findRelatedChanges(alarm(occurredAt));

        assertEquals(1, results.size());
        assertEquals("change-1", results.get(0).changeEvent().changeId());
        assertFalse(results.get(0).suggestions().isEmpty());
    }

    private static NormalizedAlarmEvent alarm(Instant occurredAt) {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fp-1",
                "PodCrashLoopBackOff",
                "alertmanager",
                "critical",
                null,
                null,
                "payment-api",
                "prod",
                "payments",
                "payment-api",
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                occurredAt,
                null,
                Map.of());
    }
}
