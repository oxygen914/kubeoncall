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
public class AlarmSilenceApprovalStore {

    private static final String KEY_PREFIX = "alarm-silence-approval:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AlarmSilenceApprovalStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public SilenceApproval approve(String fingerprint, String approvedBy, String reason, Duration ttl) {
        String normalizedFingerprint = requireText(fingerprint, "fingerprint");
        String normalizedApprovedBy = requireText(approvedBy, "approvedBy");
        String normalizedReason = text(reason, "manual silence approval");
        Duration safeTtl = safeTtl(ttl);
        Instant now = Instant.now();
        SilenceApproval approval = new SilenceApproval(
                normalizedFingerprint,
                normalizedApprovedBy,
                normalizedReason,
                now,
                now.plus(safeTtl)
        );
        try {
            redisTemplate.opsForValue().set(keyFor(normalizedFingerprint), objectMapper.writeValueAsString(toMap(approval)), safeTtl);
            return approval;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write alarm silence approval", ex);
        }
    }

    public Optional<SilenceApproval> find(String fingerprint) {
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
            SilenceApproval approval = new SilenceApproval(
                    string(map.get("fingerprint")),
                    string(map.get("approvedBy")),
                    string(map.get("reason")),
                    instant(map.get("approvedAt")),
                    instant(map.get("expiresAt"))
            );
            if (approval.isExpired(Instant.now())) {
                redisTemplate.delete(key);
                return Optional.empty();
            }
            return Optional.of(approval);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public String keyFor(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    private Map<String, Object> toMap(SilenceApproval approval) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fingerprint", approval.fingerprint());
        map.put("approvedBy", approval.approvedBy());
        map.put("reason", approval.reason());
        map.put("approvedAt", approval.approvedAt().toString());
        map.put("expiresAt", approval.expiresAt().toString());
        return map;
    }

    private Duration safeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofMinutes(30);
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

    public record SilenceApproval(
            String fingerprint,
            String approvedBy,
            String reason,
            Instant approvedAt,
            Instant expiresAt
    ) {
        public boolean isExpired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }
}
