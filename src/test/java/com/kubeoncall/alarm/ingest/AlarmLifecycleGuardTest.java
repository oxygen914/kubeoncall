package com.kubeoncall.alarm.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

class AlarmLifecycleGuardTest {

    @Test
    void shouldRejectLateFiringWithSameTimestampAsResolvedEvent() throws Exception {
        Instant occurredAt = Instant.parse("2026-07-17T10:00:00Z");
        Fixture fixture = new Fixture(AlarmStatus.RESOLVED, occurredAt);

        assertFalse(fixture.guard.shouldProcess(event(AlarmStatus.FIRING, occurredAt)));
    }

    @Test
    void shouldAllowResolvedTransitionWithSameStartTimestamp() throws Exception {
        Instant occurredAt = Instant.parse("2026-07-17T10:00:00Z");
        Fixture fixture = new Fixture(AlarmStatus.FIRING, occurredAt);

        assertTrue(fixture.guard.shouldProcess(event(AlarmStatus.RESOLVED, occurredAt)));
    }

    @Test
    void shouldRejectDuplicateLifecycleStateAtSameTimestamp() throws Exception {
        Instant occurredAt = Instant.parse("2026-07-17T10:00:00Z");
        Fixture fixture = new Fixture(AlarmStatus.FIRING, occurredAt);

        assertFalse(fixture.guard.shouldProcess(event(AlarmStatus.FIRING, occurredAt)));
    }

    private static NormalizedAlarmEvent event(AlarmStatus status, Instant occurredAt) {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fingerprint-1",
                "NodeDown",
                "alertmanager",
                "P1",
                null,
                null,
                "node-a",
                "cluster-a",
                null,
                null,
                "prometheus.up",
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                "runbook-node-down",
                status,
                occurredAt,
                "node down",
                Map.of());
    }

    private static class Fixture {
        private final AlarmLifecycleGuard guard;

        @SuppressWarnings("unchecked")
        private Fixture(AlarmStatus status, Instant occurredAt) throws Exception {
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            when(redisTemplate.opsForValue()).thenReturn(values);
            when(values.get("alarm-lifecycle:fingerprint-1"))
                    .thenReturn(objectMapper.writeValueAsString(
                            Map.of("status", status.name(), "occurredAt", occurredAt.toString())));
            guard = new AlarmLifecycleGuard(redisTemplate, objectMapper, new KubeOnCallProperties());
        }
    }
}
