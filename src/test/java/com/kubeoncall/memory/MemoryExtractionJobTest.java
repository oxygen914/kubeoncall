package com.kubeoncall.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MemoryExtractionJobTest {

    @Test
    void shouldPersistClaimedTaskAndAcknowledgeIt() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        MemoryService memoryService = mock(MemoryService.class);
        MemoryExtractionTask task = new MemoryExtractionTask(
                "task-1",
                MemoryType.INCIDENT_SUMMARY,
                MemoryScope.FINGERPRINT,
                "oom",
                "resolved",
                "payment",
                "pod-1",
                "fp-1",
                Map.of("source", "alarm"),
                0,
                Instant.now());
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask("raw", task);
        when(queue.claim()).thenReturn(Optional.of(claimed));
        when(queue.processingLeaseTtl()).thenReturn(java.time.Duration.ofMinutes(5));
        when(queue.renew(claimed)).thenReturn(true);
        MemoryExtractionPipeline pipeline = mock(MemoryExtractionPipeline.class);
        when(pipeline.extract(task)).thenReturn(result(task));
        when(memoryService.remember(any(MemoryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MemoryExtractionJob job = new MemoryExtractionJob(queue, memoryService, pipeline);

        job.processNext();

        verify(memoryService).remember(any(MemoryEntry.class));
        verify(queue).acknowledge(eq(claimed), any(MemoryExtractionPipeline.ExtractionResult.class), any());
    }

    @Test
    void shouldRetryClaimedTaskWhenPersistenceFails() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        MemoryService memoryService = mock(MemoryService.class);
        MemoryExtractionTask task = new MemoryExtractionTask(
                "task-2",
                MemoryType.SERVICE_FACT,
                MemoryScope.SERVICE,
                "owner",
                "owned by infra",
                "payment",
                null,
                null,
                Map.of("source", "ask"),
                0,
                Instant.now());
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask("raw", task);
        when(queue.claim()).thenReturn(Optional.of(claimed));
        when(queue.processingLeaseTtl()).thenReturn(java.time.Duration.ofMinutes(5));
        when(queue.renew(claimed)).thenReturn(true);
        when(memoryService.remember(any(MemoryEntry.class))).thenThrow(new IllegalStateException("ES unavailable"));
        MemoryExtractionPipeline pipeline = mock(MemoryExtractionPipeline.class);
        when(pipeline.extract(task)).thenReturn(result(task));
        MemoryExtractionJob job = new MemoryExtractionJob(queue, memoryService, pipeline);

        job.processNext();

        verify(queue).fail(any(MemoryExtractionQueue.ClaimedTask.class), any(IllegalStateException.class));
    }

    private MemoryExtractionPipeline.ExtractionResult result(MemoryExtractionTask task) {
        return new MemoryExtractionPipeline.ExtractionResult(
                java.util.List.of(new MemoryEntry(
                        null,
                        task.memoryType(),
                        task.scope(),
                        task.subject(),
                        task.content(),
                        task.service(),
                        task.resource(),
                        task.fingerprint(),
                        task.createdAt(),
                        task.createdAt(),
                        task.metadata())),
                "heuristic_fallback",
                0);
    }
}
