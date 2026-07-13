package com.kubeoncall.memory;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "ask-session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final ConversationHistoryCompactor historyCompactor;

    public RedisSessionStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties,
            ConversationHistoryCompactor historyCompactor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.historyCompactor = historyCompactor;
    }

    @Override
    public Optional<SessionSnapshot> find(String sessionId) {
        String normalized = normalize(sessionId);
        if (normalized == null) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(key(normalized));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, SessionSnapshot.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize ask session", e);
        }
    }

    @Override
    public void append(String sessionId, SessionTurn turn) {
        String normalized = normalize(sessionId);
        if (normalized == null || turn == null) {
            return;
        }
        String sessionKey = key(normalized);
        int maxAttempts = Math.max(1, properties.getMemory().getSessionAppendMaxRetries());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Boolean committed = redisTemplate.execute(new SessionCallback<>() {
                @Override
                public Boolean execute(RedisOperations operations) {
                    operations.watch(sessionKey);
                    try {
                        Object rawValue = operations.opsForValue().get(sessionKey);
                        SessionSnapshot current = deserializeSnapshot(normalized, rawValue);
                        SessionSnapshot next = historyCompactor.append(current, turn);
                        String serialized = objectMapper.writeValueAsString(next);
                        operations.multi();
                        operations.opsForValue().set(sessionKey, serialized, ttl());
                        return operations.exec() != null;
                    } catch (JsonProcessingException ex) {
                        operations.unwatch();
                        throw new IllegalStateException("Failed to update ask session", ex);
                    } catch (RuntimeException ex) {
                        operations.unwatch();
                        throw ex;
                    }
                }
            });
            if (Boolean.TRUE.equals(committed)) {
                return;
            }
        }
        throw new IllegalStateException("Concurrent ask session update exceeded retry limit for " + normalized);
    }

    private SessionSnapshot deserializeSnapshot(String sessionId, Object rawValue) throws JsonProcessingException {
        if (rawValue == null || String.valueOf(rawValue).isBlank()) {
            return new SessionSnapshot(sessionId, java.util.List.of(), null, null);
        }
        return objectMapper.readValue(String.valueOf(rawValue), SessionSnapshot.class);
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(60, properties.getMemory().getSessionTtlSeconds()));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private String normalize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sessionId.trim();
    }
}
