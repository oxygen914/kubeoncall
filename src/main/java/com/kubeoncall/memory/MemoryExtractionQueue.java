package com.kubeoncall.memory;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class MemoryExtractionQueue {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionQueue.class);
    static final String PENDING_KEY = "memory-extraction:pending";
    static final String PROCESSING_KEY = "memory-extraction:processing";
    static final String PROCESSING_LEASES_KEY = "memory-extraction:processing-leases";
    static final String DEAD_LETTER_KEY = "memory-extraction:dead-letter";
    static final String STATUS_KEY_PREFIX = "memory-extraction:status:";
    private static final DefaultRedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>(
            "local raw = redis.call('RPOP', KEYS[1])\n"
                    + "if not raw then return nil end\n"
                    + "local claimed = ARGV[2] .. '\\n' .. raw\n"
                    + "redis.call('LPUSH', KEYS[2], claimed)\n"
                    + "redis.call('ZADD', KEYS[3], ARGV[1], claimed)\n"
                    + "return raw",
            String.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>(
            "local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])\n"
                    + "redis.call('ZREM', KEYS[2], ARGV[1])\n"
                    + "return removed",
            Long.class);
    private static final DefaultRedisScript<Long> REQUEUE_SCRIPT = new DefaultRedisScript<>(
            "local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])\n"
                    + "redis.call('ZREM', KEYS[2], ARGV[1])\n"
                    + "if removed > 0 then redis.call('LPUSH', KEYS[3], ARGV[2]) end\n"
                    + "return removed",
            Long.class);
    private static final DefaultRedisScript<Long> RECLAIM_SCRIPT = new DefaultRedisScript<>(
            "local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])\n"
                    + "local reclaimed = 0\n"
                    + "for _, claimed in ipairs(expired) do\n"
                    + "  local removed = redis.call('LREM', KEYS[1], 1, claimed)\n"
                    + "  redis.call('ZREM', KEYS[2], claimed)\n"
                    + "  if removed > 0 then\n"
                    + "    local separator = string.find(claimed, '\\n', 1, true)\n"
                    + "    local raw = separator and string.sub(claimed, separator + 1) or claimed\n"
                    + "    redis.call('LPUSH', KEYS[3], raw)\n"
                    + "    reclaimed = reclaimed + 1\n"
                    + "  end\n"
                    + "end\n"
                    + "return reclaimed",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then return 0 end\n"
                    + "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])\n"
                    + "return 1",
            Long.class);

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
        reclaimExpired();
        String claimToken = UUID.randomUUID().toString();
        String raw = redisTemplate.execute(
                CLAIM_SCRIPT,
                java.util.List.of(PENDING_KEY, PROCESSING_KEY, PROCESSING_LEASES_KEY),
                String.valueOf(System.currentTimeMillis()),
                claimToken);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String claimedRaw = claimToken + "\n" + raw;
        try {
            MemoryExtractionTask task = objectMapper.readValue(raw, MemoryExtractionTask.class);
            saveStatus(MemoryExtractionStatus.processing(task));
            return Optional.of(new ClaimedTask(claimedRaw, task));
        } catch (Exception ex) {
            moveClaimed(claimedRaw, DEAD_LETTER_KEY, raw);
            metricsService.recordMemory("extract_dead_letter", "malformed", 1);
            return Optional.empty();
        }
    }

    public void acknowledge(ClaimedTask claimedTask) {
        acknowledge(claimedTask, null, java.util.List.of());
    }

    public boolean renew(ClaimedTask claimedTask) {
        if (claimedTask == null
                || claimedTask.raw() == null
                || claimedTask.raw().isBlank()) {
            return false;
        }
        Long renewed = redisTemplate.execute(
                RENEW_SCRIPT,
                java.util.List.of(PROCESSING_LEASES_KEY),
                claimedTask.raw(),
                String.valueOf(System.currentTimeMillis()));
        return Long.valueOf(1).equals(renewed);
    }

    public Duration processingLeaseTtl() {
        return Duration.ofSeconds(Math.max(1, properties.getMemory().getExtractionProcessingTimeoutSeconds()));
    }

    public void acknowledge(
            ClaimedTask claimedTask,
            MemoryExtractionPipeline.ExtractionResult result,
            java.util.List<MemoryEntry> persistedEntries) {
        acknowledgeClaim(claimedTask.raw());
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
        try {
            String raw = objectMapper.writeValueAsString(retried);
            if (retried.attempts() >= Math.max(1, properties.getMemory().getExtractionMaxAttempts())) {
                moveClaimed(claimedTask.raw(), DEAD_LETTER_KEY, raw);
                saveStatus(MemoryExtractionStatus.deadLetter(retried, failureType));
                metricsService.recordMemory("extract_dead_letter", "max_attempts", 1);
            } else {
                moveClaimed(claimedTask.raw(), PENDING_KEY, raw);
                saveStatus(MemoryExtractionStatus.retrying(retried, failureType));
                metricsService.recordMemory("extract_process", "retry", 1);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to retry memory extraction", ex);
        }
    }

    public int reclaimExpired() {
        long timeoutMillis = processingLeaseTtl().toMillis();
        int limit = Math.max(1, properties.getMemory().getExtractionReclaimLimit());
        Long reclaimed = redisTemplate.execute(
                RECLAIM_SCRIPT,
                java.util.List.of(PROCESSING_KEY, PROCESSING_LEASES_KEY, PENDING_KEY),
                String.valueOf(System.currentTimeMillis() - timeoutMillis),
                String.valueOf(limit));
        int count = reclaimed == null ? 0 : Math.max(0, reclaimed.intValue());
        if (count > 0) {
            metricsService.recordMemory("extract_reclaim", "success", count);
        }
        return count;
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

    private void acknowledgeClaim(String raw) {
        Long removed = redisTemplate.execute(ACK_SCRIPT, java.util.List.of(PROCESSING_KEY, PROCESSING_LEASES_KEY), raw);
        if (!Long.valueOf(1).equals(removed)) {
            throw new IllegalStateException("Claimed memory extraction task is no longer owned");
        }
    }

    private void moveClaimed(String claimedRaw, String destination, String destinationRaw) {
        Long removed = redisTemplate.execute(
                REQUEUE_SCRIPT,
                java.util.List.of(PROCESSING_KEY, PROCESSING_LEASES_KEY, destination),
                claimedRaw,
                destinationRaw);
        if (!Long.valueOf(1).equals(removed)) {
            throw new IllegalStateException("Claimed memory extraction task is no longer owned");
        }
    }

    private String statusKey(String taskId) {
        return STATUS_KEY_PREFIX + taskId.trim();
    }

    public record ClaimedTask(String raw, MemoryExtractionTask task) {}
}
