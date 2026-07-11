package com.kubeoncall.alarm.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class AlarmMaintenanceWindowStore {

    private static final String KEY_PREFIX = "alarm-maintenance-window:";
    private static final String ACTIVE_INDEX = "alarm-maintenance-window:active";
    private static final Duration RETENTION_AFTER_END = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AlarmMaintenanceWindowStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(AlarmMaintenanceWindow window) {
        try {
            Duration ttl = Duration.between(Instant.now(), window.endsAt()).plus(RETENTION_AFTER_END);
            redisTemplate.opsForValue().set(key(window.id()), objectMapper.writeValueAsString(window),
                    ttl.isNegative() || ttl.isZero() ? Duration.ofHours(1) : ttl);
            redisTemplate.opsForZSet().add(ACTIVE_INDEX, window.id(), window.endsAt().toEpochMilli());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize maintenance window", ex);
        }
    }

    public Optional<AlarmMaintenanceWindow> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(key(id.trim()));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, AlarmMaintenanceWindow.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize maintenance window " + id, ex);
        }
    }

    public List<AlarmMaintenanceWindow> activeAt(Instant now) {
        long epochMillis = now.toEpochMilli();
        redisTemplate.opsForZSet().removeRangeByScore(ACTIVE_INDEX, 0, epochMillis);
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(
                ACTIVE_INDEX, Math.nextUp((double) epochMillis), Double.POSITIVE_INFINITY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AlarmMaintenanceWindow> windows = new ArrayList<>();
        for (String id : ids) {
            find(id).filter(window -> window.activeAt(now)).ifPresent(windows::add);
        }
        return List.copyOf(windows);
    }

    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String normalized = id.trim();
        redisTemplate.opsForZSet().remove(ACTIVE_INDEX, normalized);
        return Boolean.TRUE.equals(redisTemplate.delete(key(normalized)));
    }

    String key(String id) {
        return KEY_PREFIX + id;
    }
}
