package com.kubeoncall.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionJobTest {

    @Test
    void shouldPersistClaimedTaskAndAcknowledgeIt() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        MemoryService memoryService = mock(MemoryService.class);
        MemoryExtractionTask task = new MemoryExtractionTask(
                "task-1", MemoryType.INCIDENT_SUMMARY, MemoryScope.FINGERPRINT, "oom", "resolved",
                "payment", "pod-1", "fp-1", Map.of("source", "alarm"), 0, Instant.now());
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask("raw", task);
        when(queue.claim()).thenReturn(Optional.of(claimed));
        MemoryExtractionJob job = new MemoryExtractionJob(queue, memoryService);

        job.processNext();

        verify(memoryService).remember(any(MemoryEntry.class));
        verify(queue).acknowledge(claimed);
    }

    @Test
    void shouldRetryClaimedTaskWhenPersistenceFails() {
        MemoryExtractionQueue queue = mock(MemoryExtractionQueue.class);
        MemoryService memoryService = mock(MemoryService.class);
        MemoryExtractionTask task = new MemoryExtractionTask(
                "task-2", MemoryType.SERVICE_FACT, MemoryScope.SERVICE, "owner", "owned by infra",
                "payment", null, null, Map.of("source", "ask"), 0, Instant.now());
        MemoryExtractionQueue.ClaimedTask claimed = new MemoryExtractionQueue.ClaimedTask("raw", task);
        when(queue.claim()).thenReturn(Optional.of(claimed));
        when(memoryService.remember(any(MemoryEntry.class))).thenThrow(new IllegalStateException("ES unavailable"));
        MemoryExtractionJob job = new MemoryExtractionJob(queue, memoryService);

        job.processNext();

        verify(queue).fail(any(MemoryExtractionQueue.ClaimedTask.class), any(IllegalStateException.class));
    }
}
