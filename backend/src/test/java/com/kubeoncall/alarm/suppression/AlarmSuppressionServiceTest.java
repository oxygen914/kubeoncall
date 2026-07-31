package com.kubeoncall.alarm.suppression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;

class AlarmSuppressionServiceTest {

    @Test
    void shouldRecordRootCauseAndSuppressCorrelatedTarget() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AlarmSuppressionRuleRepository repository = repository();
        AlarmSuppressionService service = new AlarmSuppressionService(redis, repository);
        String key = "alarm-suppression:rule:node-not-ready-suppresses-pod:cluster-a:node-a";

        service.recordSources(nodeEvent(AlarmStatus.FIRING));

        verify(values).set(key, "fp-node", Duration.ofSeconds(1800));
        when(values.get(key)).thenReturn("fp-node");
        AlarmSuppressionService.SuppressionDecision decision = service.evaluate(podEvent());
        assertTrue(decision.suppressed());
        assertEquals("node-not-ready-suppresses-pod", decision.ruleId());
        assertEquals("fp-node", decision.sourceFingerprint());
    }

    @Test
    void shouldClearRootCauseKeyOnResolvedEvent() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        AlarmSuppressionService service = new AlarmSuppressionService(redis, repository());

        service.recordSources(nodeEvent(AlarmStatus.RESOLVED));

        verify(redis).delete("alarm-suppression:rule:node-not-ready-suppresses-pod:cluster-a:node-a");
    }

    @Test
    void shouldFailOpenWhenSuppressionStorageIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenThrow(new IllegalStateException("redis unavailable"));
        AlarmSuppressionService service = new AlarmSuppressionService(redis, repository());

        assertFalse(service.evaluate(podEvent()).suppressed());
    }

    private AlarmSuppressionRuleRepository repository() {
        AlarmSuppressionRuleRepository repository = mock(AlarmSuppressionRuleRepository.class);
        AlarmSuppressionRule rule = new AlarmSuppressionRule(
                "node-not-ready-suppresses-pod",
                new AlarmSuppressionRule.Match(List.of(AlarmResourceType.NODE), List.of("*NodeNotReady*")),
                new AlarmSuppressionRule.Match(List.of(AlarmResourceType.POD), List.of()),
                List.of("cluster", "node"),
                1800,
                "Pod alarm suppressed while NodeNotReady is active on the same node");
        when(repository.findAll()).thenReturn(List.of(rule));
        when(repository.activeVersion()).thenReturn("v1");
        return repository;
    }

    private NormalizedAlarmEvent nodeEvent(AlarmStatus status) {
        return event("node-alarm", "fp-node", "KubeNodeNotReadyP0", AlarmResourceType.NODE, "node-a", Map.of(), status);
    }

    private NormalizedAlarmEvent podEvent() {
        return event(
                "pod-alarm",
                "fp-pod",
                "PodCrashLoop",
                AlarmResourceType.POD,
                "payment-pod",
                Map.of("node", "node-a"),
                AlarmStatus.FIRING);
    }

    private NormalizedAlarmEvent event(
            String alarmId,
            String fingerprint,
            String alertName,
            AlarmResourceType resourceType,
            String resourceName,
            Map<String, String> labels,
            AlarmStatus status) {
        return new NormalizedAlarmEvent(
                alarmId,
                fingerprint,
                alertName,
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                resourceType,
                resourceName,
                "cluster-a",
                "prod",
                "payment-service",
                null,
                null,
                null,
                null,
                null,
                labels,
                Map.of(),
                null,
                status,
                Instant.now(),
                alertName,
                Map.of());
    }
}
