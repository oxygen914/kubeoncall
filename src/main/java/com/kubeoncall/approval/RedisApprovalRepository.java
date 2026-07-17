package com.kubeoncall.approval;

import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalRequest;

@Repository
@Primary
@ConditionalOnProperty(
        prefix = "kubeoncall.approval",
        name = "repository",
        havingValue = "redis",
        matchIfMissing = true)
public class RedisApprovalRepository implements ApprovalRepository {

    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1])\n"
                    + "if current ~= ARGV[1] then return 0 end\n"
                    + "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])\n"
                    + "return 1",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public RedisApprovalRepository(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, KubeOnCallProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void save(ApprovalRequest request) {
        try {
            redisTemplate
                    .opsForValue()
                    .set(key(request.executionId()), objectMapper.writeValueAsString(request), ttl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize approval request", e);
        }
    }

    @Override
    public Optional<ApprovalRequest> findByExecutionId(String executionId) {
        String raw = redisTemplate.opsForValue().get(key(executionId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, ApprovalRequest.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize approval request", e);
        }
    }

    @Override
    public boolean compareAndSet(ApprovalRequest expected, ApprovalRequest updated) {
        if (expected == null
                || updated == null
                || expected.executionId() == null
                || !expected.executionId().equals(updated.executionId())) {
            return false;
        }
        try {
            Long replaced = redisTemplate.execute(
                    COMPARE_AND_SET_SCRIPT,
                    java.util.List.of(key(expected.executionId())),
                    objectMapper.writeValueAsString(expected),
                    objectMapper.writeValueAsString(updated),
                    String.valueOf(ttl().toSeconds()));
            return Long.valueOf(1).equals(replaced);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize approval transition", ex);
        }
    }

    private Duration ttl() {
        return Duration.ofSeconds(properties.getApproval().getCallbackTimeoutSeconds());
    }

    private String key(String executionId) {
        return "approval-request:" + executionId;
    }
}
