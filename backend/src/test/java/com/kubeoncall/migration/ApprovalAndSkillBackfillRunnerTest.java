package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

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
        Cursor<String> cursor = singleKeyCursor("approval-request:exec_1");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
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
    void skillStateDryRunDoesNotWrite() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stubLease(redis);
        SkillStateStore store = mock(SkillStateStore.class);
        when(store.disabledIds()).thenReturn(Set.of("payment-oom-triage", "disk-capacity-triage"));
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
                mockProvider(null),
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
                mockProvider(null),
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
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> singleKeyCursor(String key) {
        Cursor<String> cursor = mock(Cursor.class);
        AtomicBoolean consumed = new AtomicBoolean();
        when(cursor.hasNext()).thenAnswer(inv -> !consumed.getAndSet(true));
        when(cursor.next()).thenReturn(key);
        return cursor;
    }
}
