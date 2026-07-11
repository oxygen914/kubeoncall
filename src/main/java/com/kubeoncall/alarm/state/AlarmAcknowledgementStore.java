package com.kubeoncall.alarm.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AlarmAcknowledgementStore {

    private static final String KEY_PREFIX = "alarm-ack:";
    private static final String ESCALATION_KEY_PREFIX = "alarm-escalation:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AlarmAcknowledgementStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public AlarmAcknowledgement acknowledge(String fingerprint,
                                             String acknowledgedBy,
                                             String reason,
                                             Duration ttl) {
        String normalizedFingerprint = requireText(fingerprint, "fingerprint");
        String normalizedAcknowledgedBy = requireText(acknowledgedBy, "acknowledgedBy");
        String normalizedReason = text(reason, "alarm acknowledged");
        Duration safeTtl = safeTtl(ttl);
        Instant now = Instant.now();
        AlarmAcknowledgement acknowledgement = new AlarmAcknowledgement(
                normalizedFingerprint,
                normalizedAcknowledgedBy,
                normalizedReason,
                now,
                now.plus(safeTtl)
        );
        try {
            redisTemplate.opsForValue().set(
                    keyFor(normalizedFingerprint),
                    objectMapper.writeValueAsString(toMap(acknowledgement)),
                    safeTtl
            );
            redisTemplate.delete(escalationKeyFor(normalizedFingerprint));
            return acknowledgement;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to acknowledge alarm", ex);
        }
    }

    public Optional<AlarmAcknowledgement> find(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        String key = keyFor(fingerprint);
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(raw, MAP_TYPE);
            AlarmAcknowledgement acknowledgement = new AlarmAcknowledgement(
                    string(map.get("fingerprint")),
                    string(map.get("acknowledgedBy")),
                    string(map.get("reason")),
                    instant(map.get("acknowledgedAt")),
                    instant(map.get("expiresAt"))
            );
            if (acknowledgement.isExpired(Instant.now())) {
                redisTemplate.delete(key);
                return Optional.empty();
            }
            return Optional.of(acknowledgement);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public String keyFor(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    public String escalationKeyFor(String fingerprint) {
        return ESCALATION_KEY_PREFIX + fingerprint;
    }

    private Map<String, Object> toMap(AlarmAcknowledgement acknowledgement) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fingerprint", acknowledgement.fingerprint());
        map.put("acknowledgedBy", acknowledgement.acknowledgedBy());
        map.put("reason", acknowledgement.reason());
        map.put("acknowledgedAt", acknowledgement.acknowledgedAt().toString());
        map.put("expiresAt", acknowledgement.expiresAt().toString());
        return map;
    }

    private Duration safeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofHours(24);
        }
        return ttl;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static Instant instant(Object raw) {
        String value = string(raw);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    public record AlarmAcknowledgement(
            String fingerprint,
            String acknowledgedBy,
            String reason,
            Instant acknowledgedAt,
            Instant expiresAt
    ) {
        public boolean isExpired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }
}
