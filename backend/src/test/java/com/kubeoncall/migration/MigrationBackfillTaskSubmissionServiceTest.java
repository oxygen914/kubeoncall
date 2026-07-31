package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

class MigrationBackfillTaskSubmissionServiceTest {

    @Test
    void persistsTaskDomainModeAndConfiguredTimeout() {
        AsyncTaskRepository repository = mock(AsyncTaskRepository.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setBackfillDryRun(false);
        properties.getDataMigration().setBackfillTaskTimeoutSeconds(90);
        when(repository.isAvailable()).thenReturn(true);
        when(repository.create(any())).thenReturn(task());

        AsyncTaskRecord submitted = new MigrationBackfillTaskSubmissionService(repository, properties)
                .submit(MigrationBackfillTaskHandler.Domain.EXECUTION_AUDIT, null, "req_submit");

        ArgumentCaptor<AsyncTaskRepository.CreateTask> command =
                ArgumentCaptor.forClass(AsyncTaskRepository.CreateTask.class);
        org.mockito.Mockito.verify(repository).create(command.capture());
        assertThat(submitted.publicId()).isEqualTo("tsk_migration");
        assertThat(command.getValue().taskType()).isEqualTo(MigrationBackfillTaskSubmissionService.TASK_TYPE);
        assertThat(command.getValue().resourceType()).isEqualTo(MigrationBackfillTaskSubmissionService.RESOURCE_TYPE);
        assertThat(command.getValue().resourcePublicId()).isEqualTo("EXECUTION_AUDIT");
        assertThat(command.getValue().request())
                .containsEntry("domain", "EXECUTION_AUDIT")
                .containsEntry("dryRun", false)
                .containsEntry("timeoutSeconds", 90L);
    }

    private static AsyncTaskRecord task() {
        Instant now = Instant.parse("2026-07-22T12:00:00Z");
        return new AsyncTaskRecord(
                1L,
                "tsk_migration",
                "MIGRATION_BACKFILL",
                "MIGRATION",
                "EXECUTION_AUDIT",
                "dedupe",
                "PENDING",
                "queued",
                0,
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                0L,
                0,
                3,
                now,
                null,
                null,
                "req_submit",
                "req_submit",
                1L,
                now,
                now);
    }
}
