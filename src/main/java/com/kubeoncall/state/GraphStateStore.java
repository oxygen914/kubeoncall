package com.kubeoncall.state;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.domain.graph.GraphState;

@Component
public class GraphStateStore {

    private static final DefaultRedisScript<Long> RELEASE_LEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public GraphStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(GraphState state, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(state.getExecutionId()), objectMapper.writeValueAsString(state), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize graph state", e);
        }
    }

    public Optional<GraphState> find(String executionId) {
        String raw = redisTemplate.opsForValue().get(key(executionId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, GraphState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize graph state", e);
        }
    }

    public void delete(String executionId) {
        redisTemplate.delete(key(executionId));
    }

    public Optional<String> tryAcquireResumeLease(String executionId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(resumeLeaseKey(executionId), token, ttl);
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    public void releaseResumeLease(String executionId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        redisTemplate.execute(RELEASE_LEASE_SCRIPT, List.of(resumeLeaseKey(executionId)), token);
    }

    private String key(String executionId) {
        return "graph-state:" + executionId;
    }

    private String resumeLeaseKey(String executionId) {
        return "graph-state-resume-lease:" + executionId;
    }
}
