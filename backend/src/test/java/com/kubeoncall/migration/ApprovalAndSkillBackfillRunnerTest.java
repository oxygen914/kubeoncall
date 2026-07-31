package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.kubeoncall.approval.RedisApprovalRepository;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.skill.SkillStateStore;
import com.kubeoncall.skill.mysql.SkillStateRepository;

/** Verifies the Approval and Skill-state backfill runners' dry-run + skip + unavailable paths. */
class ApprovalAndSkillBackfillRunnerTest {

    @SuppressWarnings("unchecked")
    @Test
    void approvalDryRunDoesNotWrite() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubScan(redis, "approval-request:exec_1");
        stubLease(redis);
        RedisApprovalRepository redisApproval = mock(RedisApprovalRepository.class);
        ApprovalRequest request = new ApprovalRequest(
                "exec_1",
                null,
                "task_1",
                "7",
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                null,
                null,
                null,
                false,
                java.util.List.of());
        when(redisApproval.findByExecutionId("exec_1")).thenReturn(Optional.of(request));
        AtomicBoolean created = new AtomicBoolean(false);
        MySqlApprovalRepository mysqlApproval = mock(MySqlApprovalRepository.class);
        when(mysqlApproval.isAvailable()).thenReturn(true);
        doAnswer(inv -> {
                    created.set(true);
                    return null;
                })
                .when(mysqlApproval)
                .create(any());

        ApprovalBackfillRunner runner = new ApprovalBackfillRunner(
                mockProvider(redis),
                mockProvider(redisApproval),
                mockProvider(mysqlApproval),
                mockProvider(null),
                new KubeOnCallProperties());
        ApprovalBackfillRunner.BackfillResult result = runner.run(true, "req_1");

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.dryRun()).isTrue();
        assertThat(created).isFalse();
        verify(mysqlApproval, never()).create(any());
    }

    @Test
    void approvalUnavailableReturnsNote() {
        ApprovalBackfillRunner runner = new ApprovalBackfillRunner(
                mockProvider(null),
                mockProvider(null),
                mockProvider(null),
                mockProvider(null),
                new KubeOnCallProperties());
        ApprovalBackfillRunner.BackfillResult result = runner.run(false, "req_2");
        assertThat(result.scanned()).isZero();
        assertThat(result.note()).contains("unavailable");
    }

    @Test
    void approvalApplyContinuesFromPersistedCursorAndAdvancesAfterThePage() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubScan(redis, "approval-request:exec_checkpoint");
        stubLease(redis);
        RedisApprovalRepository redisApproval = mock(RedisApprovalRepository.class);
        ApprovalRequest request = new ApprovalRequest(
                "exec_checkpoint",
                null,
                "task_checkpoint",
                "7",
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                null,
                null,
                null,
                false,
                List.of());
        when(redisApproval.findByExecutionId("exec_checkpoint")).thenReturn(Optional.of(request));
        MySqlApprovalRepository mysqlApproval = mock(MySqlApprovalRepository.class);
        when(mysqlApproval.isAvailable()).thenReturn(true);
        MigrationLedgerRepository ledger = mock(MigrationLedgerRepository.class);
        when(ledger.loadScanCheckpoint("approval"))
                .thenReturn(new MigrationLedgerRepository.ScanCheckpoint("581", false));
        when(ledger.beginBatch(any(), any(), any())).thenReturn("mbt_checkpoint");
        MigrationLedgerRepository.MigrationWriteFence fence =
                new MigrationLedgerRepository.MigrationWriteFence("approval", "owner", 1L);
        when(ledger.claimWriteFence(eq("approval"), any())).thenReturn(fence);
        runFencedWrite(ledger, fence);

        ApprovalBackfillRunner runner = new ApprovalBackfillRunner(
                mockProvider(redis),
                mockProvider(redisApproval),
                mockProvider(mysqlApproval),
                mockProvider(ledger),
                new KubeOnCallProperties());

        ApprovalBackfillRunner.BackfillResult result = runner.run(false, "req_checkpoint");

        assertThat(result.checkpoint()).isEqualTo("0");
        verify(mysqlApproval).create(any());
        verify(ledger).advanceScanCheckpointFenced(fence, "approval", "0", true);
        verify(ledger).loadScanCheckpoint("approval");
    }

    @Test
    void approvalApplyMapsTheCurrentRedisTtlToMySqlExpiresAt() {
        String key = "approval-request:exec_ttl";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubScan(redis, key);
        stubLease(redis);
        when(redis.getExpire(key, TimeUnit.MILLISECONDS)).thenReturn(90_000L);
        RedisApprovalRepository redisApproval = mock(RedisApprovalRepository.class);
        ApprovalRequest request = new ApprovalRequest(
                "exec_ttl",
                null,
                "task_ttl",
                "7",
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                null,
                null,
                null,
                false,
                List.of());
        when(redisApproval.findByExecutionId("exec_ttl")).thenReturn(Optional.of(request));
        MySqlApprovalRepository mysqlApproval = mock(MySqlApprovalRepository.class);
        when(mysqlApproval.isAvailable()).thenReturn(true);
        MigrationLedgerRepository ledger = readyApprovalLedger();

        ApprovalBackfillRunner runner = new ApprovalBackfillRunner(
                mockProvider(redis),
                mockProvider(redisApproval),
                mockProvider(mysqlApproval),
                mockProvider(ledger),
                new KubeOnCallProperties());
        Instant before = Instant.now();
        ApprovalBackfillRunner.BackfillResult result = runner.run(false, "req_ttl");
        Instant after = Instant.now();

        ArgumentCaptor<MySqlApprovalRepository.CreateApproval> command =
                ArgumentCaptor.forClass(MySqlApprovalRepository.CreateApproval.class);
        verify(mysqlApproval).create(command.capture());
        assertThat(result.migrated()).isEqualTo(1);
        assertThat(command.getValue().expiresAt()).isBetween(before.plusMillis(89_000), after.plusMillis(91_000));
        assertThat(command.getValue().expiresAt()).isAfter(command.getValue().requestedAt());
    }

    @Test
    void approvalApplySkipsARecordWhoseRedisTtlHasElapsed() {
        String key = "approval-request:exec_expired";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubScan(redis, key);
        stubLease(redis);
        when(redis.getExpire(key, TimeUnit.MILLISECONDS)).thenReturn(0L);
        RedisApprovalRepository redisApproval = mock(RedisApprovalRepository.class);
        when(redisApproval.findByExecutionId("exec_expired")).thenReturn(Optional.of(approvalRequest("exec_expired")));
        MySqlApprovalRepository mysqlApproval = mock(MySqlApprovalRepository.class);
        when(mysqlApproval.isAvailable()).thenReturn(true);
        MigrationLedgerRepository ledger = readyApprovalLedger();

        ApprovalBackfillRunner runner = new ApprovalBackfillRunner(
                mockProvider(redis),
                mockProvider(redisApproval),
                mockProvider(mysqlApproval),
                mockProvider(ledger),
                new KubeOnCallProperties());

        ApprovalBackfillRunner.BackfillResult result = runner.run(false, "req_expired");

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.migrated()).isZero();
        verify(mysqlApproval, never()).create(any());
    }

    @Test
    void approvalApplyFailsClosedWhenLegacyRedisTtlIsMissing() {
        String key = "approval-request:exec_no_ttl";
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubScan(redis, key);
        stubLease(redis);
        when(redis.getExpire(key, TimeUnit.MILLISECONDS)).thenReturn(-1L);
        RedisApprovalRepository redisApproval = mock(RedisApprovalRepository.class);
        when(redisApproval.findByExecutionId("exec_no_ttl")).thenReturn(Optional.of(approvalRequest("exec_no_ttl")));
        MySqlApprovalRepository mysqlApproval = mock(MySqlApprovalRepository.class);
        when(mysqlApproval.isAvailable()).thenReturn(true);
        MigrationLedgerRepository ledger = readyApprovalLedger();

        ApprovalBackfillRunner runner = new ApprovalBackfillRunner(
                mockProvider(redis),
                mockProvider(redisApproval),
                mockProvider(mysqlApproval),
                mockProvider(ledger),
                new KubeOnCallProperties());

        ApprovalBackfillRunner.BackfillResult result = runner.run(false, "req_no_ttl");

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.migrated()).isZero();
        verify(mysqlApproval, never()).create(any());
    }

    @Test
    void skillStateDryRunDoesNotWrite() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubLease(redis);
        SkillStateStore store = mock(SkillStateStore.class);
        when(store.disabledIds()).thenReturn(Set.of("pod-oom-triage", "disk-capacity-triage"));
        AtomicBoolean upserted = new AtomicBoolean(false);
        SkillStateRepository repository = mock(SkillStateRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        doAnswer(inv -> {
                    upserted.set(true);
                    return null;
                })
                .when(repository)
                .upsert(any());

        SkillStateBackfillRunner runner = new SkillStateBackfillRunner(
                mockProvider(redis),
                mockProvider(store),
                mockProvider(repository),
                mockProvider(mock(MigrationLedgerRepository.class)),
                new KubeOnCallProperties());
        SkillStateBackfillRunner.BackfillResult result = runner.run(true, "req_3");

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.migrated()).isEqualTo(2);
        assertThat(result.dryRun()).isTrue();
        assertThat(upserted).isFalse();
        verify(repository, never()).upsert(any());
    }

    @Test
    void skillStateEmptyDisabledSetIsNoop() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubLease(redis);
        SkillStateStore store = mock(SkillStateStore.class);
        when(store.disabledIds()).thenReturn(Set.of());
        SkillStateRepository repository = mock(SkillStateRepository.class);
        when(repository.isAvailable()).thenReturn(true);

        SkillStateBackfillRunner runner = new SkillStateBackfillRunner(
                mockProvider(redis),
                mockProvider(store),
                mockProvider(repository),
                mockProvider(mock(MigrationLedgerRepository.class)),
                new KubeOnCallProperties());
        SkillStateBackfillRunner.BackfillResult result = runner.run(false, "req_4");

        assertThat(result.scanned()).isZero();
        assertThat(result.note()).contains("no disabled skills");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static void stubLease(StringRedisTemplate redis) {
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(ops.setIfAbsent(any(String.class), any(String.class), any(java.time.Duration.class)))
                .thenReturn(true);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.execute(any(RedisScript.class), any(java.util.List.class), any(String.class), any(String.class)))
                .thenReturn(1L);
        when(redis.execute(any(RedisScript.class), any(java.util.List.class), any(String.class)))
                .thenReturn(1L);
    }

    private static void stubScan(StringRedisTemplate redis, String key) {
        when(redis.execute(any(RedisCallback.class))).thenReturn(new RedisCursorScanner.Page("0", List.of(key)));
        when(redis.getExpire(any(String.class), eq(TimeUnit.MILLISECONDS))).thenReturn(60_000L);
    }

    private static ApprovalRequest approvalRequest(String executionId) {
        return new ApprovalRequest(
                executionId,
                null,
                "task_" + executionId,
                "7",
                null,
                Instant.parse("2026-07-20T01:00:00Z"),
                null,
                null,
                null,
                false,
                List.of());
    }

    private static MigrationLedgerRepository readyApprovalLedger() {
        MigrationLedgerRepository ledger = mock(MigrationLedgerRepository.class);
        when(ledger.loadScanCheckpoint("approval")).thenReturn(MigrationLedgerRepository.ScanCheckpoint.initial());
        when(ledger.beginBatch(any(), any(), any())).thenReturn("mbt_approval");
        MigrationLedgerRepository.MigrationWriteFence fence =
                new MigrationLedgerRepository.MigrationWriteFence("approval", "owner", 1L);
        when(ledger.claimWriteFence(eq("approval"), any())).thenReturn(fence);
        runFencedWrite(ledger, fence);
        return ledger;
    }

    private static void runFencedWrite(
            MigrationLedgerRepository ledger, MigrationLedgerRepository.MigrationWriteFence fence) {
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(ledger)
                .withWriteFence(eq(fence), any(Runnable.class));
    }
}
