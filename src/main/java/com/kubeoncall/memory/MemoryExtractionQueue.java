package com.kubeoncall.memory;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class MemoryExtractionQueue {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionQueue.class);
    static final String PENDING_KEY = "memory-extraction:pending";
    static final String PROCESSING_KEY = "memory-extraction:processing";
    static final String DEAD_LETTER_KEY = "memory-extraction:dead-letter";
    static final String STATUS_KEY_PREFIX = "memory-extraction:status:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final KubeOnCallMetricsService metricsService;

    public MemoryExtractionQueue(
            StringRedisTemplate redisTemplate,
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
            saveStatus(MemoryExtractionStatus.pending(task));
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
            MemoryExtractionTask task = objectMapper.readValue(raw, MemoryExtractionTask.class);
            saveStatus(MemoryExtractionStatus.processing(task));
            return Optional.of(new ClaimedTask(raw, task));
        } catch (Exception ex) {
            redisTemplate.opsForList().remove(PROCESSING_KEY, 1, raw);
            redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, raw);
            metricsService.recordMemory("extract_dead_letter", "malformed", 1);
            return Optional.empty();
        }
    }

    public void acknowledge(ClaimedTask claimedTask) {
        acknowledge(claimedTask, null, java.util.List.of());
    }

    public void acknowledge(
            ClaimedTask claimedTask,
            MemoryExtractionPipeline.ExtractionResult result,
            java.util.List<MemoryEntry> persistedEntries) {
        redisTemplate.opsForList().remove(PROCESSING_KEY, 1, claimedTask.raw());
        saveStatus(MemoryExtractionStatus.completed(claimedTask.task(), result, persistedEntries));
        metricsService.recordMemory("extract_process", "success", 1);
        if (result != null) {
            metricsService.recordMemoryExtraction(
                    result.mode(), "success", result.entries().size());
        }
    }

    public void fail(ClaimedTask claimedTask, RuntimeException failure) {
        String failureType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        MemoryExtractionTask retried = claimedTask.task().retry(failureType);
        redisTemplate.opsForList().remove(PROCESSING_KEY, 1, claimedTask.raw());
        try {
            String raw = objectMapper.writeValueAsString(retried);
            if (retried.attempts() >= Math.max(1, properties.getMemory().getExtractionMaxAttempts())) {
                redisTemplate.opsForList().leftPush(DEAD_LETTER_KEY, raw);
                saveStatus(MemoryExtractionStatus.deadLetter(retried, failureType));
                metricsService.recordMemory("extract_dead_letter", "max_attempts", 1);
            } else {
                redisTemplate.opsForList().leftPush(PENDING_KEY, raw);
                saveStatus(MemoryExtractionStatus.retrying(retried, failureType));
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
            try {
                MemoryExtractionTask task = objectMapper.readValue(raw, MemoryExtractionTask.class);
                saveStatus(MemoryExtractionStatus.pending(task));
            } catch (Exception ex) {
                log.warn(
                        "Unable to restore extraction status during replay: errorType={}",
                        ex.getClass().getSimpleName());
            }
            replayed++;
        }
        metricsService.recordMemory("extract_replay", "success", replayed);
        return replayed;
    }

    public Optional<MemoryExtractionStatus> status(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().get(statusKey(taskId));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, MemoryExtractionStatus.class));
        } catch (Exception ex) {
            log.warn(
                    "Unable to read extraction status: errorType={}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void saveStatus(MemoryExtractionStatus status) {
        try {
            Duration ttl =
                    Duration.ofSeconds(Math.max(60, properties.getMemory().getExtractionStatusTtlSeconds()));
            redisTemplate.opsForValue().set(statusKey(status.taskId()), objectMapper.writeValueAsString(status), ttl);
        } catch (Exception ex) {
            metricsService.recordMemory("extract_status", "failed", 1);
            log.warn(
                    "Unable to save extraction status: errorType={}",
                    ex.getClass().getSimpleName());
        }
    }

    private String statusKey(String taskId) {
        return STATUS_KEY_PREFIX + taskId.trim();
    }

    public record ClaimedTask(String raw, MemoryExtractionTask task) {}
}
