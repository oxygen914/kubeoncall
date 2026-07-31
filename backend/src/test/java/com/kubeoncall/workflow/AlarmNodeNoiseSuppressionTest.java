package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

class AlarmNodeNoiseSuppressionTest {

    @Test
    void shouldRecordNodeNotReadyAsSuppressionSource() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setNodeNotReadySuppressionTtlSeconds(1800);
        AlarmNodeNoiseSuppression service = new AlarmNodeNoiseSuppression(redisTemplate, properties);

        service.recordSource(nodeEvent(AlarmStatus.FIRING));

        verify(values).set("alarm-suppression:node:cluster-a:node-a", "fp-node", Duration.ofSeconds(1800));
    }

    @Test
    void shouldSuppressPodWhenItsNodeHasAnActiveNodeNotReadyAlarm() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey("alarm-suppression:node:cluster-a:node-a")).thenReturn(true);
        AlarmNodeNoiseSuppression service = new AlarmNodeNoiseSuppression(redisTemplate, new KubeOnCallProperties());

        AlarmNodeNoiseSuppression.Decision decision = service.evaluate(podEvent());

        assertTrue(decision.suppressed());
        assertEquals("node-a", decision.nodeName());
        assertEquals("alarm-suppression:node:cluster-a:node-a", decision.suppressionKey());
    }

    @Test
    void shouldNotSuppressPodWithoutNodeLabel() {
        AlarmNodeNoiseSuppression service =
                new AlarmNodeNoiseSuppression(mock(StringRedisTemplate.class), new KubeOnCallProperties());

        assertFalse(service.evaluate(podEvent(Map.of())).suppressed());
    }

    private NormalizedAlarmEvent nodeEvent(AlarmStatus status) {
        return event("node-alarm", "fp-node", "KubeNodeNotReadyP0", AlarmResourceType.NODE, "node-a", Map.of(), status);
    }

    private NormalizedAlarmEvent podEvent() {
        return podEvent(Map.of("node", "node-a"));
    }

    private NormalizedAlarmEvent podEvent(Map<String, String> labels) {
        return event(
                "pod-alarm",
                "fp-pod",
                "PodCrashLoop",
                AlarmResourceType.POD,
                "payment-pod",
                labels,
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
