package com.kubeoncall.identity;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Redis-backed {@link SessionStore}. Each session is a JSON payload under {@code koc:session:<id>}
 * with a TTL set to the absolute timeout. A reverse index {@code koc:session:user:<userId>} tracks
 * live session ids so password change / disable can revoke every session for a user without
 * scanning keyspace. All reads tolerate corrupt JSON by returning empty, so a bad payload can never
 * authenticate a request.
 */
@Component("identitySessionStore")
@ConditionalOnBean(IdentityRepository.class)
public class RedisSessionStore implements SessionStore {

    private static final String SESSION_PREFIX = "koc:session:";
    private static final String USER_INDEX_PREFIX = "koc:session:user:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public RedisSessionStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.mysqlEnabled = mysqlEnabled;
    }

    @Override
    public String create(SessionRecord record, Duration ttl) {
        String sessionId = SessionIdGenerator.generate();
        write(sessionId, record, ttl);
        return sessionId;
    }

    @Override
    public Optional<SessionRecord> find(String sessionId) {
        String normalized = SessionIdGenerator.normalize(sessionId);
        if (normalized == null) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(key(normalized));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, SessionRecord.class));
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    @Override
    public void replace(String sessionId, SessionRecord record, Duration ttl) {
        String normalized = SessionIdGenerator.normalize(sessionId);
        if (normalized == null) {
            return;
        }
        write(normalized, record, ttl);
    }

    @Override
    public void delete(String sessionId) {
        String normalized = SessionIdGenerator.normalize(sessionId);
        if (normalized == null) {
            return;
        }
        SessionRecord existing = find(normalized).orElse(null);
        redisTemplate.delete(key(normalized));
        if (existing != null) {
            redisTemplate.opsForSet().remove(userIndexKey(existing.userId()), normalized);
        }
    }

    @Override
    public void deleteByUser(long userId) {
        Set<String> sessions = redisTemplate.opsForSet().members(userIndexKey(userId));
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (String sessionId : sessions) {
            redisTemplate.delete(key(sessionId));
        }
        redisTemplate.delete(userIndexKey(userId));
    }

    private void write(String sessionId, SessionRecord record, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(key(sessionId), json, ttl);
            redisTemplate.opsForSet().add(userIndexKey(record.userId()), sessionId);
            redisTemplate.expire(userIndexKey(record.userId()), ttl);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize session record", ex);
        }
    }

    private static String key(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private static String userIndexKey(long userId) {
        return USER_INDEX_PREFIX + userId;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }
}
