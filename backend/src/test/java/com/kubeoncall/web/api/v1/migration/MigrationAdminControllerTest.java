package com.kubeoncall.web.api.v1.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.migration.ActiveAlarmBackfillRunner;
import com.kubeoncall.migration.ChangeEventBackfillRunner;
import com.kubeoncall.migration.LegacyAlarmCommandBackfillRunner;
import com.kubeoncall.migration.LegacyAlarmCommandPreflightService;
import com.kubeoncall.migration.LegacyExecutionAuditBackfillRunner;
import com.kubeoncall.migration.MigrationBackfillTaskHandler;
import com.kubeoncall.migration.MigrationBackfillTaskSubmissionService;
import com.kubeoncall.migration.MigrationLedgerRepository;
import com.kubeoncall.migration.RedisInventoryService;
import com.kubeoncall.web.api.v1.V1ApiErrorCode;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class MigrationAdminControllerTest {

    private final ActiveAlarmBackfillRunner activeAlarmBackfillRunner = mock(ActiveAlarmBackfillRunner.class);
    private final com.kubeoncall.migration.ApprovalBackfillRunner approvalBackfillRunner =
            mock(com.kubeoncall.migration.ApprovalBackfillRunner.class);
    private final com.kubeoncall.migration.SkillStateBackfillRunner skillStateBackfillRunner =
            mock(com.kubeoncall.migration.SkillStateBackfillRunner.class);
    private final LegacyAlarmCommandBackfillRunner legacyAlarmCommandBackfillRunner =
            mock(LegacyAlarmCommandBackfillRunner.class);
    private final LegacyExecutionAuditBackfillRunner legacyExecutionAuditBackfillRunner =
            mock(LegacyExecutionAuditBackfillRunner.class);
    private final ChangeEventBackfillRunner changeEventBackfillRunner = mock(ChangeEventBackfillRunner.class);
    private final KubeOnCallProperties properties = new KubeOnCallProperties();
    private final MigrationBackfillTaskSubmissionService taskSubmissionService =
            mock(MigrationBackfillTaskSubmissionService.class);
    private MigrationAdminController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new MigrationAdminController(
                mock(RedisInventoryService.class),
                activeAlarmBackfillRunner,
                approvalBackfillRunner,
                skillStateBackfillRunner,
                mock(LegacyAlarmCommandPreflightService.class),
                legacyAlarmCommandBackfillRunner,
                legacyExecutionAuditBackfillRunner,
                changeEventBackfillRunner,
                mock(ObjectProvider.class),
                provider(taskSubmissionService),
                properties,
                mock(V1Security.class));
    }

    @Test
    void rejectsEveryExplicitApplyWithoutTheSeparateConfirmation() {
        assertRejected(() -> controller.backfillAlarmAcknowledgements(false, false));
        assertRejected(() -> controller.backfillAlarmSilences(false, false));
        assertRejected(() -> controller.backfillAlarmRecoveries(false, false));
        assertRejected(() -> controller.backfillExecutionAudit(false, false));
        assertRejected(() -> controller.backfillActiveAlarm(false, false, null));
        assertRejected(() -> controller.backfillApproval(false, false, null));
        assertRejected(() -> controller.backfillSkillState(false, false, null));
        assertRejected(() -> controller.backfillChangeEvent(false, false, null));

        verifyNoInteractions(
                activeAlarmBackfillRunner,
                approvalBackfillRunner,
                skillStateBackfillRunner,
                legacyAlarmCommandBackfillRunner,
                legacyExecutionAuditBackfillRunner,
                changeEventBackfillRunner);
    }

    @Test
    void rejectsAnOmittedDryRunWhenTheConfiguredDefaultWouldApply() {
        properties.getDataMigration().setBackfillDryRun(false);

        assertRejected(() -> controller.backfillActiveAlarm(null, null, null));
        verifyNoInteractions(activeAlarmBackfillRunner);
    }

    @Test
    void allowsAnExplicitlyConfirmedApply() {
        when(activeAlarmBackfillRunner.run(eq(false), any()))
                .thenReturn(new ActiveAlarmBackfillRunner.BackfillResult(1, 1, 0, 0, null, "done", false));

        assertThat(controller.backfillActiveAlarm(false, true, null).data().get("dryRun"))
                .isEqualTo(false);
    }

    @Test
    void statusExposesPerDomainRetirementState() {
        properties
                .getDataMigration()
                .setApproval(new com.kubeoncall.common.config.DataMigrationProperties.DomainRetirement("MYSQL", true));

        java.util.Map<String, Object> data = controller.status().data();

        assertThat(data.get("approval")).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> approval = (java.util.Map<String, Object>) data.get("approval");
        assertThat(approval).containsEntry("factSource", "MYSQL").containsEntry("legacyWriteDisabled", true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> skillState = (java.util.Map<String, Object>) data.get("skillState");
        assertThat(skillState).containsEntry("factSource", "REDIS").containsEntry("legacyWriteDisabled", false);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> executionAudit = (java.util.Map<String, Object>) data.get("executionAudit");
        assertThat(executionAudit).containsEntry("factSource", "REDIS").containsEntry("legacyWriteDisabled", false);
    }

    @Test
    void queuesAConfirmedApplyAsDurableTask() {
        com.kubeoncall.task.AsyncTaskRecord task = new com.kubeoncall.task.AsyncTaskRecord(
                1L,
                "tsk_migration",
                "MIGRATION_BACKFILL",
                "MIGRATION",
                "ACTIVE_ALARM",
                "dedupe",
                "PENDING",
                "queued",
                0,
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                null,
                null,
                null,
                0L,
                0,
                3,
                java.time.Instant.now(),
                null,
                null,
                "req_1",
                "req_1",
                1L,
                java.time.Instant.now(),
                java.time.Instant.now());
        when(taskSubmissionService.submit(eq(MigrationBackfillTaskHandler.Domain.ACTIVE_ALARM), eq(false), any()))
                .thenReturn(task);

        MigrationAdminController.MigrationTaskAccepted accepted = controller
                .submitBackfillTask(MigrationBackfillTaskHandler.Domain.ACTIVE_ALARM, false, true)
                .data();
        assertThat(accepted.taskId()).isEqualTo("tsk_migration");
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(accepted.dryRun()).isFalse();
    }

    @Test
    void resolvesAnOpenDiffWithTheAuthenticatedOperator() {
        MigrationLedgerRepository ledger = mock(MigrationLedgerRepository.class);
        when(ledger.isAvailable()).thenReturn(true);
        when(ledger.resolveDiff("mdiff_1", "RESOLVED", "checked", "admin")).thenReturn(true);
        V1Security security = mock(V1Security.class);
        com.kubeoncall.identity.UserAccount user = new com.kubeoncall.identity.UserAccount(
                1L,
                "usr_1",
                "admin",
                "Admin",
                "admin@example.invalid",
                "hash",
                "bcrypt",
                1L,
                "ACTIVE",
                1L,
                1L,
                null,
                null,
                null,
                0,
                java.util.Set.of(),
                java.util.Set.of());
        when(security.requirePermission(any()))
                .thenReturn(new V1Principal(user, java.util.Set.of(), V1Principal.AuthMethod.SESSION));
        controller = new MigrationAdminController(
                mock(RedisInventoryService.class),
                activeAlarmBackfillRunner,
                approvalBackfillRunner,
                skillStateBackfillRunner,
                mock(LegacyAlarmCommandPreflightService.class),
                legacyAlarmCommandBackfillRunner,
                legacyExecutionAuditBackfillRunner,
                changeEventBackfillRunner,
                provider(ledger),
                provider(taskSubmissionService),
                properties,
                security);

        assertThat(controller
                        .resolveDiff("mdiff_1", "RESOLVED", "checked")
                        .data()
                        .get("resolutionStatus"))
                .isEqualTo("RESOLVED");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static void assertRejected(ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOf(V1ApiException.class).satisfies(error -> {
            V1ApiException exception = (V1ApiException) error;
            assertThat(exception.status()).isEqualTo(400);
            assertThat(exception.code()).isEqualTo(V1ApiErrorCode.INVALID_REQUEST);
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
