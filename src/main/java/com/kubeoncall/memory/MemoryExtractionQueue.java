package com.kubeoncall.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MemoryExtractionQueue {

    static final String PENDING_KEY = "memory-extraction:pending";
    static final String PROCESSING_KEY = "memory-extraction:processing";
    static final String DEAD_LETTER_KEY = "memory-extraction:dead-letter";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;

    public MemoryExtractionQueue(StringRedisTemplate redisTemplate,
                                 ObjectMapper objectMapper,
                                 KubeOnCallProperties properties,
                                 KubeOnCallMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    public void enqueue(MemoryExtractionTask task) {
        try {
            redisTemplate.opsForList().leftPush(PENDING_KEY, objectMapper.writeValueAsString(task));
            metricsService.recordMemory("extract_enqueue", "success", 1);
        } catch (Exception ex) {
            metricsService.recordMemory("extract_enqueue", "failed", 1);
            throw new IllegalStateException("Failed to enqueue memory extraction", ex);
        }
    }

    public Optional<ClaimedTask> claim() {
        String raw = redisTemplate.opsForList().rightPopAndLeftPush(PENDING_KEY, PROCESSING_KEY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ClaimedTask(raw, objectMapper.readValue(raw, MemoryExtractionTask.class)));
        } catch (Exception ex) {
            redisTemplate.opsForList().remove(PROCESSING_KEY, 1, raw);
            redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, raw);
            metricsService.recordMemory("extract_dead_letter", "malformed", 1);
            return Optional.empty();
        }
    }

    public void acknowledge(ClaimedTask claimedTask) {
        redisTemplate.opsForList().remove(PROCESSING_KEY, 1, claimedTask.raw());
        metricsService.recordMemory("extract_process", "success", 1);
    }

    public void fail(ClaimedTask claimedTask, RuntimeException failure) {
        MemoryExtractionTask retried = claimedTask.task().retry(failure == null ? "unknown" : failure.getMessage());
        redisTemplate.opsForList().remove(PROCESSING_KEY, 1, claimedTask.raw());
        try {
            String raw = objectMapper.writeValueAsString(retried);
            if (retried.attempts() >= Math.max(1, properties.getMemory().getExtractionMaxAttempts())) {
                redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, raw);
                metricsService.recordMemory("extract_dead_letter", "max_attempts", 1);
            } else {
                redisTemplate.opsForList().leftPush(PENDING_KEY, raw);
                metricsService.recordMemory("extract_process", "retry", 1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to retry memory extraction", ex);
        }
    }

    public int replayDeadLetters(int limit) {
        int replayed = 0;
        int safeLimit = Math.max(1, Math.min(1000, limit));
        while (replayed < safeLimit) {
            String raw = redisTemplate.opsForList().rightPop(DEAD_LETTER_KEY);
            if (raw == null) {
                break;
            }
            redisTemplate.opsForList().leftPush(PENDING_KEY, raw);
            replayed++;
        }
        metricsService.recordMemory("extract_replay", "success", replayed);
        return replayed;
    }

    public record ClaimedTask(String raw, MemoryExtractionTask task) {
    }
}
