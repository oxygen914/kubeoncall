package com.kubeoncall.task.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.migration.ActiveAlarmBackfillRunner;
import com.kubeoncall.migration.ApprovalBackfillRunner;
import com.kubeoncall.migration.LegacyAlarmCommandBackfillRunner;
import com.kubeoncall.migration.LegacyExecutionAuditBackfillRunner;
import com.kubeoncall.migration.MigrationBackfillTaskHandler;
import com.kubeoncall.migration.MigrationRunControl;
import com.kubeoncall.migration.SkillStateBackfillRunner;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

class MigrationBackfillTaskHandlerTest {

    @Test
    void adaptsApprovalBackfillIntoAFencedDurableTaskResult() {
        ActiveAlarmBackfillRunner activeAlarm = mock(ActiveAlarmBackfillRunner.class);
        ApprovalBackfillRunner approval = mock(ApprovalBackfillRunner.class);
        SkillStateBackfillRunner skill = mock(SkillStateBackfillRunner.class);
        LegacyAlarmCommandBackfillRunner commands = mock(LegacyAlarmCommandBackfillRunner.class);
        LegacyExecutionAuditBackfillRunner audit = mock(LegacyExecutionAuditBackfillRunner.class);
        AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);
        MigrationRunControl runControl = mock(MigrationRunControl.class);
        AsyncTaskContext context = new AsyncTaskContext(
                task("APPROVAL", true),
                "owner_1",
                () -> true,
                () -> false,
                null,
                Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC));
        when(tasks.updateProgress(eq("tsk_migration"), eq("owner_1"), eq(7L), any(), any(Integer.class), any()))
                .thenReturn(true);
        when(runControl.bind(context)).thenReturn(() -> {});
        when(approval.run(true, "req_migration"))
                .thenReturn(new ApprovalBackfillRunner.BackfillResult(8, 4, 3, 1, "42", "done", true));

        Map<String, Object> result = new MigrationBackfillTaskHandler(
                        activeAlarm, approval, skill, commands, audit, tasks, runControl)
                .handle(context)
                .result();

        assertThat(result)
                .containsEntry("domain", "APPROVAL")
                .containsEntry("taskId", "tsk_migration")
                .containsEntry("scanned", 8L)
                .containsEntry("migrated", 4L)
                .containsEntry("dryRun", true);
        verify(approval).run(true, "req_migration");
        verify(tasks).updateProgress(eq("tsk_migration"), eq("owner_1"), eq(7L), eq("finalizing"), eq(95), any());
    }

    private static AsyncTaskRecord task(String domain, boolean dryRun) {
        Instant now = Instant.parse("2026-07-22T12:00:00Z");
        return new AsyncTaskRecord(
                1L,
                "tsk_migration",
                "MIGRATION_BACKFILL",
                "MIGRATION",
                domain,
                "dedupe",
                "RUNNING",
                "queued",
                0,
                Map.of("domain", domain, "dryRun", dryRun, "timeoutSeconds", 1800),
                Map.of(),
                null,
                null,
                "owner_1",
                now.plusSeconds(300),
                7L,
                1,
                3,
                now,
                now,
                null,
                "req_migration",
                "req_migration",
                1L,
                now,
                now);
    }
}
