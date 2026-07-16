package com.kubeoncall.alarm.ingest;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.inbox.AlarmInboxUnavailableException;
import com.kubeoncall.common.config.KubeOnCallProperties;

/** Prevents a late delivery from reopening an alarm after a newer lifecycle transition was handled. */
@Service
public class AlarmLifecycleGuard {

    private static final String KEY_PREFIX = "alarm-lifecycle:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public AlarmLifecycleGuard(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean shouldProcess(NormalizedAlarmEvent event) {
        try {
            String raw = redisTemplate.opsForValue().get(key(event.fingerprint()));
            if (raw == null || raw.isBlank()) {
                return true;
            }
            LifecycleState state = objectMapper.readValue(raw, LifecycleState.class);
            Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
            return !occurredAt.isBefore(state.occurredAt());
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to read alarm lifecycle state", ex);
        }
    }

    public void recordProcessed(NormalizedAlarmEvent event) {
        try {
            Instant occurredAt = event.occurredAt() == null ? Instant.now() : event.occurredAt();
            LifecycleState state = new LifecycleState(event.status(), occurredAt);
            redisTemplate
                    .opsForValue()
                    .set(key(event.fingerprint()), objectMapper.writeValueAsString(state), retention());
        } catch (Exception ex) {
            throw new AlarmInboxUnavailableException("Unable to persist alarm lifecycle state", ex);
        }
    }

    private Duration retention() {
        return Duration.ofHours(Math.max(1, properties.getAlarm().getInboxRetentionHours()));
    }

    private String key(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    record LifecycleState(AlarmStatus status, Instant occurredAt) {}
}
