package com.kubeoncall.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "ask-session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final ConversationHistoryCompactor historyCompactor;

    @Autowired
    public RedisSessionStore(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             KubeOnCallProperties properties,
                             ConversationHistoryCompactor historyCompactor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.historyCompactor = historyCompactor;
    }

    public RedisSessionStore(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             KubeOnCallProperties properties) {
        this(redisTemplate, objectMapper, properties,
                new ConversationHistoryCompactor(properties, new TokenBudget()));
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
        SessionSnapshot current = find(normalized)
                .orElseGet(() -> new SessionSnapshot(normalized, java.util.List.of(), null, null));
        SessionSnapshot next = historyCompactor.append(current, turn);
        try {
            redisTemplate.opsForValue().set(key(normalized), objectMapper.writeValueAsString(next), ttl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ask session", e);
        }
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
