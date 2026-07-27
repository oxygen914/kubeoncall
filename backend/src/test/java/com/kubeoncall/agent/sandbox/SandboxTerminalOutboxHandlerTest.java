package com.kubeoncall.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.outbox.OutboxEvent;
import com.kubeoncall.task.AsyncTaskRepository;

class SandboxTerminalOutboxHandlerTest {

    @Test
    void createsOneIdempotentRecoveryTaskForTerminalRun() throws Exception {
        AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
        SandboxTerminalOutboxHandler handler = new SandboxTerminalOutboxHandler(tasks, new ObjectMapper());

        handler.handle(event(Map.of("runId", "sbx_123", "executionId", "wfe_123")));

        ArgumentCaptor<AsyncTaskRepository.CreateTask> command =
                ArgumentCaptor.forClass(AsyncTaskRepository.CreateTask.class);
        verify(tasks).create(command.capture());
        assertThat(command.getValue().taskType()).isEqualTo(SandboxTerminalOutboxHandler.TASK_TYPE);
        assertThat(command.getValue().dedupeKey()).isEqualTo("sandbox-workflow-recovery:sbx_123");
        assertThat(command.getValue().request())
                .containsEntry("runId", "sbx_123")
                .containsEntry("executionId", "wfe_123")
                .containsEntry("outboxEventId", "evt_123");
    }

    @Test
    void treatsDurableDuplicateAsSuccessfulOutboxRedelivery() throws Exception {
        AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
        when(tasks.create(any())).thenThrow(new DuplicateKeyException("uk_async_task_dedupe"));

        new SandboxTerminalOutboxHandler(tasks, new ObjectMapper())
                .handle(event(Map.of("runId", "sbx_123", "executionId", "wfe_123")));

        verify(tasks).create(any());
    }

    @Test
    void rejectsMalformedTerminalEventBeforeCreatingTask() {
        AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
        SandboxTerminalOutboxHandler handler = new SandboxTerminalOutboxHandler(tasks, new ObjectMapper());

        assertThatThrownBy(() -> handler.handle(event(Map.of("runId", "sbx_123"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionId");
        verify(tasks, never()).create(any());
    }

    private static OutboxEvent event(Map<String, Object> payload) throws Exception {
        return new OutboxEvent(
                1L,
                "evt_123",
                "sandbox-run",
                "sbx_123",
                SandboxTerminalOutboxHandler.EVENT_TYPE,
                1,
                new ObjectMapper().writeValueAsString(payload),
                "req_123",
                1,
                5,
                Instant.parse("2026-07-27T00:00:00Z"),
                null);
    }
}
