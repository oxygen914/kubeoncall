package com.kubeoncall.alarm.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class AlarmRecoveryStore {

    private static final String KEY_PREFIX = "alarm-recovery:";
    private static final String DUE_KEY = "alarm-recovery:due";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AlarmRecoveryStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void savePending(AlarmRecoveryState state, Duration ttl) {
        write(state, ttl);
        if (!state.manualConfirmationRequired()) {
            redisTemplate.opsForZSet().add(DUE_KEY, state.fingerprint(), state.confirmAfter().toEpochMilli());
        }
    }

    public void saveFinal(AlarmRecoveryState state, Duration ttl) {
        write(state, ttl);
        redisTemplate.opsForZSet().remove(DUE_KEY, state.fingerprint());
    }

    public Optional<AlarmRecoveryState> find(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(keyFor(fingerprint));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, AlarmRecoveryState.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public Set<String> dueFingerprints(Instant now, int limit) {
        Set<String> values = redisTemplate.opsForZSet().rangeByScore(
                DUE_KEY,
                0,
                now.toEpochMilli(),
                0,
                Math.max(1, limit)
        );
        return values == null ? Set.of() : new LinkedHashSet<>(values);
    }

    public String keyFor(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    private void write(AlarmRecoveryState state, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(
                    keyFor(state.fingerprint()),
                    objectMapper.writeValueAsString(state),
                    ttl
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist alarm recovery state", ex);
        }
    }
}
