package com.kubeoncall.service.audit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.audit.ExecutionAuditRecord;

@Repository
@Primary
@ConditionalOnProperty(prefix = "kubeoncall.audit", name = "repository", havingValue = "redis", matchIfMissing = true)
public class RedisExecutionAuditRepository implements ExecutionAuditRepository {

    private static final String KEY_PREFIX = "execution-audit:";
    private static final String INDEX_KEY = "execution-audit:index";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public RedisExecutionAuditRepository(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void save(ExecutionAuditRecord record) {
        String key = key(record.executionId(), record.occurredAt());
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(record), ttl());
            redisTemplate.opsForZSet().add(INDEX_KEY, key, score(record.occurredAt()));
            deleteBefore(Instant.now().minus(properties.getAudit().getRetentionHours(), ChronoUnit.HOURS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize execution audit record", e);
        }
    }

    @Override
    public List<ExecutionAuditRecord> findAll() {
        Set<String> keys = redisTemplate.opsForZSet().reverseRange(INDEX_KEY, 0, -1);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(this::read)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ExecutionAuditRecord::occurredAt))
                .toList();
    }

    @Override
    public List<ExecutionAuditRecord> findRecent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> keys = redisTemplate.opsForZSet().reverseRange(INDEX_KEY, 0, limit - 1);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(this::read)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ExecutionAuditRecord::occurredAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public void deleteBefore(Instant threshold) {
        if (threshold == null) {
            return;
        }
        Double max = score(threshold) - 1;
        Set<String> keys = redisTemplate.opsForZSet().rangeByScore(INDEX_KEY, Double.NEGATIVE_INFINITY, max);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        redisTemplate.delete(keys);
        redisTemplate.opsForZSet().removeRangeByScore(INDEX_KEY, Double.NEGATIVE_INFINITY, max);
    }

    private ExecutionAuditRecord read(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            redisTemplate.opsForZSet().remove(INDEX_KEY, key);
            return null;
        }
        try {
            return objectMapper.readValue(raw, ExecutionAuditRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize execution audit record", e);
        }
    }

    private Duration ttl() {
        return Duration.ofHours(properties.getAudit().getRetentionHours());
    }

    private String key(String executionId, Instant occurredAt) {
        long epoch = occurredAt == null ? System.currentTimeMillis() : occurredAt.toEpochMilli();
        return KEY_PREFIX + executionId + ":" + epoch;
    }

    private double score(Instant occurredAt) {
        return occurredAt == null ? System.currentTimeMillis() : occurredAt.toEpochMilli();
    }
}
