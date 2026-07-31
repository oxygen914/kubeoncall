package com.kubeoncall.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.audit.OutboxWriter.OutboxEvent;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.worker.AsyncTaskWorker.Outcome;
import com.kubeoncall.task.worker.AsyncTaskWorker.RunResult;

class TaskOutboxLifecycleListenerTest {

    @Test
    void publishesRetryProjectionWithPublicStatusAndAggregateContext() {
        OutboxWriter outboxWriter = org.mockito.Mockito.mock(OutboxWriter.class);
        when(outboxWriter.isAvailable()).thenReturn(true);
        TaskOutboxLifecycleListener listener = new TaskOutboxLifecycleListener(outboxWriter);
        Instant nextAttemptAt = Instant.parse("2026-07-21T03:01:00Z");
        RunResult result = new RunResult(
                Outcome.RETRY_SCHEDULED,
                "tsk_123",
                "KNOWLEDGE_IMPORT",
                2,
                "UPSTREAM_TIMEOUT",
                "Embedding service timed out",
                nextAttemptAt);

        listener.onTransition(task(), result);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxWriter).enqueue(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.aggregateType()).isEqualTo("task");
        assertThat(event.aggregatePublicId()).isEqualTo("tsk_123");
        assertThat(event.eventType()).isEqualTo("task.updated");
        assertThat(event.requestId()).isEqualTo("req_123");
        assertThat(event.payload())
                .containsEntry("status", "RETRY")
                .containsEntry("resourceType", "knowledge_import")
                .containsEntry("resourceId", "kimp_123")
                .containsEntry("nextAttemptAt", nextAttemptAt);
    }

    @Test
    void skipsProjectionWhenOutboxIsUnavailable() {
        OutboxWriter outboxWriter = org.mockito.Mockito.mock(OutboxWriter.class);
        TaskOutboxLifecycleListener listener = new TaskOutboxLifecycleListener(outboxWriter);

        listener.onTransition(
                task(), new RunResult(Outcome.SUCCEEDED, "tsk_123", "KNOWLEDGE_IMPORT", 1, null, null, null));

        verify(outboxWriter, never()).enqueue(org.mockito.ArgumentMatchers.any());
    }

    private static AsyncTaskRecord task() {
        Instant now = Instant.parse("2026-07-21T03:00:00Z");
        return new AsyncTaskRecord(
                42L,
                "tsk_123",
                "KNOWLEDGE_IMPORT",
                "knowledge_import",
                "kimp_123",
                "dedupe-123",
                "RUNNING",
                "index",
                25,
                Map.of(),
                Map.of(),
                null,
                null,
                "worker-a",
                now.plusSeconds(30),
                7L,
                2,
                5,
                now,
                now,
                null,
                "req_123",
                "trace_123",
                2L,
                now.minusSeconds(30),
                now);
    }
}
