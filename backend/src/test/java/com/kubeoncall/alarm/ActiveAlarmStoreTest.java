package com.kubeoncall.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;

class ActiveAlarmStoreTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Test
    void shouldWriteNewActiveAlarmState() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("alarm-active:fp-1")).thenReturn(null);
        ObjectMapper objectMapper = new ObjectMapper();
        ActiveAlarmStore store = new ActiveAlarmStore(redisTemplate, objectMapper, new KubeOnCallProperties());

        ActiveAlarmState state = store.record(event("fp-1", AlarmStatus.FIRING), evaluation(AlarmSeverity.P1), "fp-1");

        assertEquals("fp-1", state.fingerprint());
        assertEquals(1, state.count());
        assertEquals(AlarmSeverity.P1, state.severity());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("alarm-active:fp-1"), jsonCaptor.capture(), eq(Duration.ofSeconds(86400)));
        Map<String, Object> stored = objectMapper.readValue(jsonCaptor.getValue(), MAP_TYPE);
        assertEquals("FIRING", stored.get("status"));
        assertEquals("P1", stored.get("severity"));
        assertNotNull(stored.get("firstSeen"));
        assertNotNull(stored.get("lastSeen"));
    }

    @Test
    void shouldIncrementExistingActiveAlarmCount() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("fingerprint", "fp-2");
        existing.put("severity", "P2");
        existing.put("status", "FIRING");
        existing.put("firstSeen", "2026-07-08T01:00:00Z");
        existing.put("lastSeen", "2026-07-08T01:05:00Z");
        existing.put("count", 2);
        when(valueOperations.get("alarm-active:fp-2")).thenReturn(objectMapper.writeValueAsString(existing));
        ActiveAlarmStore store = new ActiveAlarmStore(redisTemplate, objectMapper, new KubeOnCallProperties());

        ActiveAlarmState state = store.record(event("fp-2", AlarmStatus.FIRING), evaluation(AlarmSeverity.P0), "fp-2");

        assertEquals(3, state.count());
        assertEquals(Instant.parse("2026-07-08T01:00:00Z"), state.firstSeen());
        assertEquals(AlarmSeverity.P0, state.severity());
    }

    @Test
    void shouldPreserveOriginalSeverityPolicyAndCountWhenResolved() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("fingerprint", "fp-3");
        existing.put("severity", "P0");
        existing.put("status", "FIRING");
        existing.put("policyId", "host-high-cpu-p0");
        existing.put("firstSeen", "2026-07-08T01:00:00Z");
        existing.put("lastSeen", "2026-07-08T01:05:00Z");
        existing.put("count", 4);
        when(valueOperations.get("alarm-active:fp-3")).thenReturn(objectMapper.writeValueAsString(existing));
        ActiveAlarmStore store = new ActiveAlarmStore(redisTemplate, objectMapper, new KubeOnCallProperties());

        ActiveAlarmState state =
                store.record(event("fp-3", AlarmStatus.RESOLVED), evaluation(AlarmSeverity.INFO), "fp-3");

        assertEquals(AlarmSeverity.P0, state.severity());
        assertEquals("host-high-cpu-p0", state.policyId());
        assertEquals(4, state.count());
        assertEquals(AlarmStatus.RESOLVED, state.status());
    }

    private static NormalizedAlarmEvent event(String fingerprint, AlarmStatus status) {
        return new NormalizedAlarmEvent(
                "alarm-1",
                fingerprint,
                "HostHighCpuUsageP1",
                "prometheus",
                "warning",
                AlarmSeverity.P2,
                com.kubeoncall.alarm.domain.AlarmResourceType.NODE,
                "node-a",
                "cluster-a",
                "monitoring",
                "infra",
                "host.cpu.usage_percent",
                72.0,
                70.0,
                "%",
                "10m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-host-cpu-high",
                status,
                Instant.parse("2026-07-08T01:10:00Z"),
                "cpu high",
                Map.of());
    }

    private static AlarmEvaluationResult evaluation(AlarmSeverity severity) {
        return new AlarmEvaluationResult(
                true,
                null,
                "host-high-cpu-p1",
                severity,
                70.0,
                "runbook-host-cpu-high",
                "cpu > 70",
                "15m",
                "host-resource",
                "matched",
                java.util.List.of());
    }
}
