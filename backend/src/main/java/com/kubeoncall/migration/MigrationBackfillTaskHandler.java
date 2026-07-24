package com.kubeoncall.migration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;

/** Adapts every WBS-11 backfill domain to the fenced durable async-task worker. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MigrationBackfillTaskHandler implements AsyncTaskHandler {

    public enum Domain {
        ACTIVE_ALARM,
        APPROVAL,
        SKILL_STATE,
        ALARM_ACKNOWLEDGEMENT,
        ALARM_SILENCE,
        ALARM_RECOVERY,
        EXECUTION_AUDIT,
        CHANGE_EVENT;

        public static Domain parse(Object value) {
            try {
                return Domain.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Unsupported migration backfill domain: " + value, ex);
            }
        }
    }

    private final ActiveAlarmBackfillRunner activeAlarmRunner;
    private final ApprovalBackfillRunner approvalRunner;
    private final SkillStateBackfillRunner skillStateRunner;
    private final LegacyAlarmCommandBackfillRunner alarmCommandRunner;
    private final LegacyExecutionAuditBackfillRunner executionAuditRunner;
    private final ChangeEventBackfillRunner changeEventRunner;
    private final AsyncTaskRepository taskRepository;
    private final MigrationRunControl runControl;

    public MigrationBackfillTaskHandler(
            ActiveAlarmBackfillRunner activeAlarmRunner,
            ApprovalBackfillRunner approvalRunner,
            SkillStateBackfillRunner skillStateRunner,
            LegacyAlarmCommandBackfillRunner alarmCommandRunner,
            LegacyExecutionAuditBackfillRunner executionAuditRunner,
            ChangeEventBackfillRunner changeEventRunner,
            AsyncTaskRepository taskRepository,
            MigrationRunControl runControl) {
        this.activeAlarmRunner = activeAlarmRunner;
        this.approvalRunner = approvalRunner;
        this.skillStateRunner = skillStateRunner;
        this.alarmCommandRunner = alarmCommandRunner;
        this.executionAuditRunner = executionAuditRunner;
        this.changeEventRunner = changeEventRunner;
        this.taskRepository = taskRepository;
        this.runControl = runControl;
    }

    @Override
    public String taskType() {
        return MigrationBackfillTaskSubmissionService.TASK_TYPE;
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        Domain domain = Domain.parse(context.task().request().get("domain"));
        Boolean dryRun = bool(context.task().request().get("dryRun"));
        updateProgress(context, "backfill:" + domain.name().toLowerCase(Locale.ROOT), 5);
        Map<String, Object> result;
        try (MigrationRunControl.Scope ignored = runControl.bind(context)) {
            result = switch (domain) {
                case ACTIVE_ALARM ->
                    result(activeAlarmRunner.run(dryRun, context.task().requestId()));
                case APPROVAL ->
                    result(approvalRunner.run(dryRun, context.task().requestId()));
                case SKILL_STATE ->
                    result(skillStateRunner.run(dryRun, context.task().requestId()));
                case ALARM_ACKNOWLEDGEMENT ->
                    result(alarmCommandRunner.run(
                            LegacyAlarmCommandBackfillRunner.Kind.ACKNOWLEDGEMENT,
                            dryRun,
                            context.task().requestId()));
                case ALARM_SILENCE ->
                    result(alarmCommandRunner.run(
                            LegacyAlarmCommandBackfillRunner.Kind.SILENCE,
                            dryRun,
                            context.task().requestId()));
                case ALARM_RECOVERY ->
                    result(alarmCommandRunner.run(
                            LegacyAlarmCommandBackfillRunner.Kind.RECOVERY,
                            dryRun,
                            context.task().requestId()));
                case EXECUTION_AUDIT ->
                    result(executionAuditRunner.run(dryRun, context.task().requestId()));
                case CHANGE_EVENT ->
                    result(changeEventRunner.run(dryRun, context.task().requestId()));
            };
        }
        context.requireValidLease();
        updateProgress(context, "finalizing", 95);
        result.put("domain", domain.name());
        result.put("taskId", context.task().publicId());
        return new HandlerResult(result);
    }

    private void updateProgress(AsyncTaskContext context, String stage, int progress) {
        context.requireValidLease();
        if (!taskRepository.updateProgress(
                context.task().publicId(),
                context.ownerToken(),
                context.fencingToken(),
                stage,
                progress,
                Instant.now())) {
            throw new IllegalStateException("Migration task progress lost its ownership fence");
        }
    }

    private static Boolean bool(Object value) {
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Map<String, Object> result(ActiveAlarmBackfillRunner.BackfillResult result) {
        return result(
                result.scanned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.checkpoint(),
                result.note(),
                result.dryRun());
    }

    private static Map<String, Object> result(ApprovalBackfillRunner.BackfillResult result) {
        return result(
                result.scanned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.checkpoint(),
                result.note(),
                result.dryRun());
    }

    private static Map<String, Object> result(SkillStateBackfillRunner.BackfillResult result) {
        return result(
                result.scanned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.checkpoint(),
                result.note(),
                result.dryRun());
    }

    private static Map<String, Object> result(LegacyAlarmCommandBackfillRunner.BackfillResult result) {
        return result(
                result.scanned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.checkpoint(),
                result.note(),
                result.dryRun());
    }

    private static Map<String, Object> result(LegacyExecutionAuditBackfillRunner.BackfillResult result) {
        return result(
                result.scanned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.checkpoint(),
                result.note(),
                result.dryRun());
    }

    private static Map<String, Object> result(ChangeEventBackfillRunner.BackfillResult result) {
        return result(
                result.scanned(),
                result.migrated(),
                result.skipped(),
                result.failed(),
                result.checkpoint(),
                result.note(),
                result.dryRun());
    }

    private static Map<String, Object> result(
            long scanned, long migrated, long skipped, long failed, String checkpoint, String note, boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", scanned);
        result.put("migrated", migrated);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("checkpoint", checkpoint);
        result.put("note", note);
        result.put("dryRun", dryRun);
        return result;
    }
}
