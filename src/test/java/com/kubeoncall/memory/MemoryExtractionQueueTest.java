package com.kubeoncall.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

class MemoryExtractionQueueTest {

    @Test
    void shouldClaimFromPendingAndAcknowledgeProcessingEntry() throws Exception {
        Fixture fixture = new Fixture();
        MemoryExtractionTask task = task(0);
        String raw = fixture.objectMapper.writeValueAsString(task);
        when(fixture.listOperations.rightPopAndLeftPush(
                        MemoryExtractionQueue.PENDING_KEY, MemoryExtractionQueue.PROCESSING_KEY))
                .thenReturn(raw);

        MemoryExtractionQueue.ClaimedTask claimed = fixture.queue.claim().orElseThrow();
        fixture.queue.acknowledge(claimed);

        assertEquals(task, claimed.task());
        verify(fixture.listOperations).remove(MemoryExtractionQueue.PROCESSING_KEY, 1, raw);
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

        ArgumentCaptor<String> deadLetter = ArgumentCaptor.forClass(String.class);
        verify(fixture.listOperations).leftPush(eq(MemoryExtractionQueue.DEAD_LETTER_KEY), deadLetter.capture());
        MemoryExtractionTask stored = fixture.objectMapper.readValue(deadLetter.getValue(), MemoryExtractionTask.class);
        assertEquals(3, stored.attempts());
        assertEquals("IllegalStateException", stored.metadata().get("last_error"));
        verify(fixture.metrics).recordMemory("extract_dead_letter", "max_attempts", 1);
    }

    @Test
    void shouldReplayDeadLetterTasksToPendingQueue() throws Exception {
        Fixture fixture = new Fixture();
        String raw = fixture.objectMapper.writeValueAsString(task(3));
        when(fixture.listOperations.rightPop(MemoryExtractionQueue.DEAD_LETTER_KEY))
                .thenReturn(raw, null);

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

        private Fixture() {
            when(redisTemplate.opsForList()).thenReturn(listOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            queue = new MemoryExtractionQueue(redisTemplate, objectMapper, properties, metrics);
        }
    }
}
