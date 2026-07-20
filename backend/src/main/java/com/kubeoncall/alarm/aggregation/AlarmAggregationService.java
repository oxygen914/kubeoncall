package com.kubeoncall.alarm.aggregation;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;

/** Groups noisy firing alarms by policy and operational scope while always preserving P0/recovery handling. */
@Service
public class AlarmAggregationService {

    private static final String KEY_PREFIX = "alarm-aggregate:";

    private final StringRedisTemplate redisTemplate;
    private final KubeOnCallProperties properties;

    public AlarmAggregationService(StringRedisTemplate redisTemplate, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public AggregationDecision evaluate(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        AlarmSeverity severity = evaluation == null ? event.severity() : evaluation.finalSeverity();
        if (severity == null || severity == AlarmSeverity.P0 || event.status() == AlarmStatus.RESOLVED) {
            return AggregationDecision.representative("not_aggregated", 1);
        }
        Duration window = windowFor(severity);
        if (window.isZero()) {
            return AggregationDecision.representative("not_aggregated", 1);
        }
        String key = key(event, evaluation, severity);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, window);
            }
            long effectiveCount = count == null ? 1 : count;
            return new AggregationDecision(effectiveCount == 1, key, effectiveCount, window);
        } catch (RuntimeException ex) {
            // A cache outage must never suppress a potentially actionable alarm.
            return AggregationDecision.representative("aggregation_unavailable", 1);
        }
    }

    private Duration windowFor(AlarmSeverity severity) {
        return switch (severity) {
            case P1 -> Duration.ofSeconds(Math.max(0, properties.getAlarm().getP1AggregationWindowSeconds()));
            case P2 -> Duration.ofSeconds(Math.max(0, properties.getAlarm().getP2AggregationWindowSeconds()));
            case P3, INFO ->
                Duration.ofSeconds(Math.max(0, properties.getAlarm().getP3AggregationWindowSeconds()));
            case P0 -> Duration.ZERO;
        };
    }

    private String key(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation, AlarmSeverity severity) {
        String policy = evaluation == null || evaluation.policyId() == null ? event.alertName() : evaluation.policyId();
        String scope =
                firstNonBlank(event.service(), event.resourceName(), event.namespace(), event.cluster(), "global");
        return KEY_PREFIX + safe(policy) + ":" + severity.name() + ":" + safe(scope);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "global";
    }

    private String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_.-]+", "_");
    }

    public record AggregationDecision(boolean representative, String key, long count, Duration window) {

        static AggregationDecision representative(String key, long count) {
            return new AggregationDecision(true, key, count, Duration.ZERO);
        }
    }
}
