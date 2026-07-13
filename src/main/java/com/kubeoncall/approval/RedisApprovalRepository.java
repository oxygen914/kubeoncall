package com.kubeoncall.approval;

import java.time.Duration;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalRequest;

@Repository
@Primary
public class RedisApprovalRepository implements ApprovalRepository {

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

    private Duration ttl() {
        return Duration.ofSeconds(properties.getApproval().getCallbackTimeoutSeconds());
    }

    private String key(String executionId) {
        return "approval-request:" + executionId;
    }
}
