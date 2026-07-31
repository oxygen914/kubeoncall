package com.kubeoncall.alarm.aggregation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

class AlarmAggregationServiceTest {

    @Test
    void shouldSuppressLaterP1AlarmInSameScopeWindow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L, 2L);
        AlarmAggregationService service = new AlarmAggregationService(redis, new KubeOnCallProperties());

        var first = service.evaluate(event(AlarmStatus.FIRING), evaluation());
        var second = service.evaluate(event(AlarmStatus.FIRING), evaluation());

        assertTrue(first.representative());
        assertFalse(second.representative());
        verify(redis).expire(anyString(), eq(Duration.ofSeconds(300)));
    }

    @Test
    void shouldNeverAggregateRecoveryEvent() {
        AlarmAggregationService service =
                new AlarmAggregationService(mock(StringRedisTemplate.class), new KubeOnCallProperties());

        assertTrue(service.evaluate(event(AlarmStatus.RESOLVED), evaluation()).representative());
    }

    private static NormalizedAlarmEvent event(AlarmStatus status) {
        return new NormalizedAlarmEvent(
                "a-1",
                "fp-1",
                "ServiceErrors",
                "alertmanager",
                "warning",
                AlarmSeverity.P1,
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
                status,
                Instant.now(),
                null,
                Map.of());
    }

    private static AlarmEvaluationResult evaluation() {
        return new AlarmEvaluationResult(
                true,
                null,
                "service-errors-p1",
                AlarmSeverity.P1,
                null,
                null,
                null,
                null,
                null,
                "matched",
                java.util.List.of());
    }
}
