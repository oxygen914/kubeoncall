package com.kubeoncall.alarm.correlation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.DataMigrationProperties.ChangeEventReadSource;
import com.kubeoncall.common.config.DataMigrationProperties.ChangeEventWriteMode;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.migration.MigrationLedgerRepository;

class ChangeCorrelationServiceTest {

    @Test
    void shouldRankRecentSameResourceImageChange() {
        InMemoryChangeEventRepository store = new InMemoryChangeEventRepository();
        ChangeCorrelationService service = serviceWithRedis(store, new KubeOnCallProperties());
        Instant occurredAt = Instant.parse("2026-07-15T00:30:00Z");
        service.record(new ChangeEvent(
                "change-1",
                "deployment_image_change",
                "pipeline",
                occurredAt.minusSeconds(120),
                "deployment",
                "payment-api",
                "payments",
                "prod",
                Map.of(),
                "ci-cd",
                "build-1"));

        var results = service.findRelatedChanges(alarm(occurredAt));

        assertEquals(1, results.size());
        assertEquals("change-1", results.get(0).changeEvent().changeId());
        assertFalse(results.get(0).suggestions().isEmpty());
    }

    @Test
    void mysqlWriteModePersistsToMysqlWhenAvailable() {
        RedisChangeEventRepository redis = redisSpy(new InMemoryChangeEventRepository());
        MysqlChangeEventRepository mysql = mock(MysqlChangeEventRepository.class);
        when(mysql.isAvailable()).thenReturn(true);
        when(mysql.saveIfAbsent(any())).thenReturn(true);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setChangeEventWriteMode(ChangeEventWriteMode.MYSQL_PRIMARY);
        ChangeCorrelationService service =
                new ChangeCorrelationService(redis, provider(mysql), provider(null), properties);

        boolean accepted = service.recordIfAbsent(event("change-mysql"));

        assertTrue(accepted);
        verify(mysql).saveIfAbsent(any());
        verify(redis, never()).saveIfAbsent(any());
    }

    @Test
    void shadowReadServesMysqlAndRecordsDiffOnMismatch() {
        InMemoryChangeEventRepository redisStore = new InMemoryChangeEventRepository();
        RedisChangeEventRepository redis = redisSpy(redisStore);
        MysqlChangeEventRepository mysql = mock(MysqlChangeEventRepository.class);
        when(mysql.isAvailable()).thenReturn(true);
        // MySQL has the event, Redis does not -> mismatch.
        when(mysql.findBetween(any(), any(), any(), any())).thenReturn(List.of(event("change-shadow")));
        MigrationLedgerRepository ledger = mock(MigrationLedgerRepository.class);

        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setChangeEventReadSource(ChangeEventReadSource.SHADOW);
        ChangeCorrelationService service =
                new ChangeCorrelationService(redis, provider(mysql), provider(ledger), properties);

        List<ChangeEvent> events =
                service.findBetween(Instant.EPOCH, Instant.parse("2026-07-15T01:00:00Z"), "prod", "payments");

        assertEquals(1, events.size());
        assertEquals("change-shadow", events.get(0).changeId());
        verify(ledger).recordShadowComparison("change-event", true);
        verify(ledger).recordDiff(eq("change-event"), eq(null), eq("SET_MISMATCH"), any(), any(), eq(null));
    }

    private static ChangeEvent event(String changeId) {
        return new ChangeEvent(
                changeId,
                "deployment_image_change",
                "pipeline",
                Instant.parse("2026-07-15T00:28:00Z"),
                "deployment",
                "payment-api",
                "payments",
                "prod",
                Map.of(),
                "ci-cd",
                changeId);
    }

    private static ChangeCorrelationService serviceWithRedis(
            InMemoryChangeEventRepository store, KubeOnCallProperties properties) {
        return new ChangeCorrelationService(redisSpy(store), provider(null), provider(null), properties);
    }

    /** Wraps an in-memory store behind a mock Redis repository so the service routes to it as Redis. */
    private static RedisChangeEventRepository redisSpy(InMemoryChangeEventRepository store) {
        RedisChangeEventRepository redis = mock(RedisChangeEventRepository.class);
        when(redis.saveIfAbsent(any())).thenAnswer(inv -> store.saveIfAbsent(inv.getArgument(0)));
        when(redis.findBetween(any(), any(), any(), any()))
                .thenAnswer(inv -> store.findBetween(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        return redis;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private static NormalizedAlarmEvent alarm(Instant occurredAt) {
        return new NormalizedAlarmEvent(
                "alarm-1",
                "fp-1",
                "PodCrashLoopBackOff",
                "alertmanager",
                "critical",
                null,
                null,
                "payment-api",
                "prod",
                "payments",
                "payment-api",
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                occurredAt,
                null,
                Map.of());
    }
}
