package com.kubeoncall.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.MysqlChangeEventRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Verifies the change-event backfill dry-run path: it walks the Redis ZSET members, deserializes
 * each, but does NOT call the MySQL repository (no writes). The apply path and idempotent replay
 * are covered by the integration test against a real MySQL + Redis.
 */
class ChangeEventBackfillRunnerTest {

    @SuppressWarnings("unchecked")
    @Test
    void dryRunScansButDoesNotWrite() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChangeEvent event = new ChangeEvent(
                "change-1",
                "deployment_image_change",
                "pipeline",
                Instant.parse("2026-07-20T01:00:00Z"),
                "deployment",
                "payment-api",
                "payments",
                "prod",
                Map.of("ref", "abc"),
                "github",
                "change-1");
        ObjectMapper objectMapper = objectMapper();
        String member = objectMapper.writeValueAsString(event);
        stubZscan(redis, member);
        stubLease(redis);

        MysqlChangeEventRepository mysql = mock(MysqlChangeEventRepository.class);
        when(mysql.isAvailable()).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setBackfillDryRun(true);

        ChangeEventBackfillRunner runner = new ChangeEventBackfillRunner(
                provider(redis), provider(mysql), provider(null), provider(objectMapper), properties);

        ChangeEventBackfillRunner.BackfillResult result = runner.run(true, "req_1");

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.dryRun()).isTrue();
        verify(mysql, never()).saveIfAbsent(any());
    }

    @Test
    void applyWritesEachMemberThroughTheFence() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChangeEvent event = new ChangeEvent(
                "change-2",
                "configmap_change",
                "pipeline",
                Instant.parse("2026-07-20T02:00:00Z"),
                "ConfigMap",
                "payment-config",
                "payments",
                "prod",
                Map.of(),
                "gitlab",
                "change-2");
        ObjectMapper objectMapper = objectMapper();
        stubZscan(redis, objectMapper.writeValueAsString(event));
        stubLease(redis);

        MysqlChangeEventRepository mysql = mock(MysqlChangeEventRepository.class);
        when(mysql.isAvailable()).thenReturn(true);
        MigrationLedgerRepository ledger = mock(MigrationLedgerRepository.class);
        when(ledger.loadScanCheckpoint("change-event"))
                .thenReturn(new MigrationLedgerRepository.ScanCheckpoint("0", false));
        when(ledger.beginBatch(any(), any(), any())).thenReturn("mbt_ce");
        MigrationLedgerRepository.MigrationWriteFence fence =
                new MigrationLedgerRepository.MigrationWriteFence("change-event", "owner", 1L);
        when(ledger.claimWriteFence(eq("change-event"), any())).thenReturn(fence);
        when(ledger.itemAlreadyMigrated(anyString(), eq("change-event"))).thenReturn(false);
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(ledger)
                .withWriteFence(eq(fence), any(Runnable.class));

        ChangeEventBackfillRunner runner = new ChangeEventBackfillRunner(
                provider(redis), provider(mysql), provider(ledger), provider(objectMapper), new KubeOnCallProperties());

        ChangeEventBackfillRunner.BackfillResult result = runner.run(false, "req_2");

        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.dryRun()).isFalse();
        verify(mysql).saveIfAbsent(event);
        verify(ledger).advanceScanCheckpointFenced(fence, "change-event", "0", true);
    }

    @Test
    void unavailableDependenciesReturnNote() {
        ChangeEventBackfillRunner runner = new ChangeEventBackfillRunner(
                ChangeEventBackfillRunnerTest.<StringRedisTemplate>provider(null),
                ChangeEventBackfillRunnerTest.<MysqlChangeEventRepository>provider(null),
                ChangeEventBackfillRunnerTest.<MigrationLedgerRepository>provider(null),
                ChangeEventBackfillRunnerTest.<ObjectMapper>provider(null),
                new KubeOnCallProperties());
        ChangeEventBackfillRunner.BackfillResult result = runner.run(false, "req_3");
        assertThat(result.scanned()).isZero();
        assertThat(result.note()).contains("unavailable");
    }

    /** Matches the production ObjectMapper, which registers the JSR-310 module for Instant. */
    private static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @SuppressWarnings("unchecked")
    private static void stubZscan(StringRedisTemplate redis, String member) {
        when(redis.execute(any(RedisCallback.class))).thenReturn(new RedisCursorScanner.Page("0", List.of(member)));
    }

    /** Stubs the single-flight lease acquisition so the runner proceeds past the lock check. */
    @SuppressWarnings("unchecked")
    private static void stubLease(StringRedisTemplate redis) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.setIfAbsent(any(String.class), any(String.class), any(java.time.Duration.class)))
                .thenReturn(true);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.execute(any(RedisScript.class), any(java.util.List.class), any(String.class)))
                .thenReturn(1L);
        when(redis.execute(any(RedisScript.class), any(java.util.List.class), any(String.class), any(String.class)))
                .thenReturn(1L);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
