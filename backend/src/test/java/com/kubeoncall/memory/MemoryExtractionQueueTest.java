package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

class MemoryExtractionQueueTest {

    @Test
    void shouldClaimFromPendingAndAcknowledgeProcessingEntry() throws Exception {
        Fixture fixture = new Fixture();
        MemoryExtractionTask task = task(0);
        String raw = fixture.objectMapper.writeValueAsString(task);
        fixture.claimedRaw = raw;

        MemoryExtractionQueue.ClaimedTask claimed = fixture.queue.claim().orElseThrow();
        fixture.queue.acknowledge(claimed);

        assertEquals(task, claimed.task());
        assertTrue(claimed.raw().endsWith("\n" + raw));
        verify(fixture.redisTemplate, atLeastOnce()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(fixture.metrics).recordMemory("extract_process", "success", 1);
    }

    @Test
    void shouldMoveTaskToDeadLetterAfterMaxAttempts() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.getMemory().setExtractionMaxAttempts(3);
        MemoryExtractionTask task = task(2);
        String raw = fixture.objectMapper.writeValueAsString(task);
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask(raw, task);

        fixture.queue.fail(claimed, new IllegalStateException("ES unavailable"));

        verify(fixture.metrics).recordMemory("extract_dead_letter", "max_attempts", 1);
    }

    @Test
    void shouldReplayDeadLetterTasksToPendingQueue() throws Exception {
        Fixture fixture = new Fixture();
        String raw = fixture.objectMapper.writeValueAsString(task(3));
        when(fixture.listOperations.rightPop(MemoryExtractionQueue.DEAD_LETTER_KEY))
                .thenReturn(raw)
                .thenReturn(null);

        int replayed = fixture.queue.replayDeadLetters(10);

        assertEquals(1, replayed);
        verify(fixture.listOperations).leftPush(MemoryExtractionQueue.PENDING_KEY, raw);
        verify(fixture.metrics).recordMemory("extract_replay", "success", 1);
    }

    @Test
    void shouldExposeCompletedExtractionStatus() throws Exception {
        Fixture fixture = new Fixture();
        MemoryExtractionTask task = task(0);
        String raw = fixture.objectMapper.writeValueAsString(task);
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask(raw, task);
        MemoryEntry entry = new MemoryEntry(
                "memory-1",
                task.memoryType(),
                task.scope(),
                task.subject(),
                task.content(),
                task.service(),
                task.resource(),
                task.fingerprint(),
                task.createdAt(),
                task.createdAt(),
                task.metadata());
        MemoryExtractionPipeline.ExtractionResult result =
                new MemoryExtractionPipeline.ExtractionResult(java.util.List.of(entry), "llm_structured", 0);

        fixture.queue.acknowledge(claimed, result, java.util.List.of(entry));

        ArgumentCaptor<String> statusJson = ArgumentCaptor.forClass(String.class);
        verify(fixture.valueOperations)
                .set(
                        eq(MemoryExtractionQueue.STATUS_KEY_PREFIX + task.id()),
                        statusJson.capture(),
                        any(java.time.Duration.class));
        MemoryExtractionStatus status =
                fixture.objectMapper.readValue(statusJson.getValue(), MemoryExtractionStatus.class);
        assertEquals("COMPLETED", status.status());
        assertEquals("llm_structured", status.extractionMode());
        assertEquals(java.util.List.of("memory-1"), status.memoryIds());
        verify(fixture.metrics).recordMemoryExtraction("llm_structured", "success", 1);
    }

    @Test
    void shouldReadStoredStatus() throws Exception {
        Fixture fixture = new Fixture();
        MemoryExtractionStatus stored = MemoryExtractionStatus.pending(task(0));
        when(fixture.valueOperations.get(MemoryExtractionQueue.STATUS_KEY_PREFIX + "task-1"))
                .thenReturn(fixture.objectMapper.writeValueAsString(stored));

        MemoryExtractionStatus status = fixture.queue.status("task-1").orElseThrow();

        assertTrue(status.status().equals("PENDING"));
    }

    @Test
    void shouldReclaimExpiredProcessingTasks() {
        Fixture fixture = new Fixture();
        fixture.reclaimed = 2L;

        int reclaimed = fixture.queue.reclaimExpired();

        assertEquals(2, reclaimed);
        verify(fixture.metrics).recordMemory("extract_reclaim", "success", 2);
    }

    @Test
    void shouldRenewOwnedProcessingLease() {
        Fixture fixture = new Fixture();
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask("token\nraw", task(0));

        assertTrue(fixture.queue.renew(claimed));

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(fixture.redisTemplate).execute(script.capture(), anyList(), any(Object[].class));
        assertTrue(script.getValue().getScriptAsString().contains("ZSCORE"));
        assertTrue(script.getValue().getScriptAsString().contains("ZADD"));
    }

    private static MemoryExtractionTask task(int attempts) {
        return new MemoryExtractionTask(
                "task-1",
                MemoryType.SERVICE_FACT,
                MemoryScope.SERVICE,
                "owner",
                "owned by infra",
                "payment-service",
                null,
                null,
                Map.of("source", "ask"),
                attempts,
                Instant.now());
    }

    private static class Fixture {
        private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        @SuppressWarnings("unchecked")
        private final ListOperations<String, String> listOperations = mock(ListOperations.class);

        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final KubeOnCallProperties properties = new KubeOnCallProperties();
        private final KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        private final MemoryExtractionQueue queue;
        private String claimedRaw;
        private Long reclaimed = 0L;

        private Fixture() {
            when(redisTemplate.opsForList()).thenReturn(listOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                    .thenAnswer(invocation -> {
                        RedisScript<?> script = invocation.getArgument(0);
                        if (script.getResultType() == String.class) {
                            return claimedRaw;
                        }
                        if (script.getScriptAsString().contains("ZRANGEBYSCORE")) {
                            return reclaimed;
                        }
                        return 1L;
                    });
            queue = new MemoryExtractionQueue(redisTemplate, objectMapper, properties, metrics);
        }
    }
}
