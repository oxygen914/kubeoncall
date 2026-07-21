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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Verifies the backfill runner's dry-run path: it scans Redis, reads the active-alarm state, but
 * does NOT call the projection (no MySQL writes), and skips keys whose state is absent. The apply
 * path is covered by the integration test against a real MySQL.
 */
class ActiveAlarmBackfillRunnerTest {

    @SuppressWarnings("unchecked")
    @Test
    void dryRunScansButDoesNotProject() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = singleKeyCursor("alarm-active:fp_dry");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        stubLease(redis);

        ActiveAlarmStore store = mock(ActiveAlarmStore.class);
        ActiveAlarmState state = new ActiveAlarmState(
                "fp_dry",
                "am_1",
                "NodeCpuHigh",
                "prod",
                null,
                null,
                "worker-01",
                AlarmSeverity.P2,
                AlarmStatus.FIRING,
                "policy-1",
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:10:00Z"),
                3);
        when(store.find("fp_dry")).thenReturn(Optional.of(state));

        AtomicBoolean projected = new AtomicBoolean(false);
        AlarmIncidentProjection projection = mock(AlarmIncidentProjection.class);
        when(projection.isAvailable()).thenReturn(true);
        doAnswer(inv -> {
                    projected.set(true);
                    return null;
                })
                .when(projection)
                .projectBackfill(any());

        ObjectProvider<StringRedisTemplate> redisProvider = mockProvider(redis);
        ObjectProvider<ActiveAlarmStore> storeProvider = mockProvider(store);
        ObjectProvider<AlarmIncidentProjection> projectionProvider = mockProvider(projection);
        ObjectProvider<MigrationLedgerRepository> ledgerProvider =
                ActiveAlarmBackfillRunnerTest.<MigrationLedgerRepository>mockProvider(null);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setBackfillDryRun(true);

        ActiveAlarmBackfillRunner runner = new ActiveAlarmBackfillRunner(
                redisProvider, storeProvider, projectionProvider, ledgerProvider, properties);

        ActiveAlarmBackfillRunner.BackfillResult result = runner.run(true, "req_1");

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.dryRun()).isTrue();
        assertThat(projected).isFalse();
        verify(projection, never()).projectBackfill(any());
    }

    @Test
    void missingStateIsSkipped() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Cursor<String> cursor = singleKeyCursor("alarm-active:fp_missing");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        stubLease(redis);
        ActiveAlarmStore store = mock(ActiveAlarmStore.class);
        when(store.find("fp_missing")).thenReturn(Optional.empty());
        AlarmIncidentProjection projection = mock(AlarmIncidentProjection.class);
        when(projection.isAvailable()).thenReturn(true);

        ActiveAlarmBackfillRunner runner = new ActiveAlarmBackfillRunner(
                mockProvider(redis),
                mockProvider(store),
                mockProvider(projection),
                ActiveAlarmBackfillRunnerTest.<MigrationLedgerRepository>mockProvider(null),
                new KubeOnCallProperties());

        ActiveAlarmBackfillRunner.BackfillResult result = runner.run(true, "req_2");
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.migrated()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void unavailableDependenciesReturnNote() {
        ActiveAlarmBackfillRunner runner = new ActiveAlarmBackfillRunner(
                ActiveAlarmBackfillRunnerTest.<StringRedisTemplate>mockProvider(null),
                ActiveAlarmBackfillRunnerTest.<ActiveAlarmStore>mockProvider(null),
                ActiveAlarmBackfillRunnerTest.<AlarmIncidentProjection>mockProvider(null),
                ActiveAlarmBackfillRunnerTest.<MigrationLedgerRepository>mockProvider(null),
                new KubeOnCallProperties());
        ActiveAlarmBackfillRunner.BackfillResult result = runner.run(false, "req_3");
        assertThat(result.scanned()).isZero();
        assertThat(result.note()).contains("unavailable");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> mockProvider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> singleKeyCursor(String key) {
        Cursor<String> cursor = mock(Cursor.class);
        AtomicBoolean consumed = new AtomicBoolean();
        when(cursor.hasNext()).thenAnswer(inv -> !consumed.getAndSet(true));
        when(cursor.next()).thenReturn(key);
        return cursor;
    }

    /** Stubs the single-flight lease acquisition so the runner proceeds past the lock check. */
    @SuppressWarnings("unchecked")
    private static void stubLease(StringRedisTemplate redis) {
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(ops.setIfAbsent(any(String.class), any(String.class), any(java.time.Duration.class)))
                .thenReturn(true);
        when(redis.opsForValue()).thenReturn(ops);
    }
}
