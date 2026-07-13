package com.kubeoncall.state;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.domain.graph.GraphState;

@Component
public class GraphStateStore {

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

    private String key(String executionId) {
        return "graph-state:" + executionId;
    }
}
