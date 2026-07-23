package com.kubeoncall.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.DataMigrationProperties;
import com.kubeoncall.common.config.KubeOnCallProperties;

class AlarmEventPreparationServiceTest {

    @Test
    void mysqlPrimaryWritesMySqlBeforeRedisCompatibilityState() {
        Fixtures fixtures = fixtures(DataMigrationProperties.AlarmWriteMode.MYSQL_PRIMARY);
        when(fixtures.projection.projectPrimary(any(), any())).thenReturn(fixtures.primaryState);
        when(fixtures.store.record(any(), any(), any())).thenReturn(fixtures.redisState);

        AlarmEventPreparationService.PreparedAlarm prepared = fixtures.service.prepare(fixtures.event);

        assertThat(prepared.activeState()).isEqualTo(fixtures.redisState);
        InOrder order = inOrder(fixtures.projection, fixtures.store);
        order.verify(fixtures.projection).projectPrimary(any(), any());
        order.verify(fixtures.store).record(any(), any(), any());
        verify(fixtures.projection, never()).project(any(), any(), any());
    }

    @Test
    void mysqlPrimaryDoesNotCallRedisWhenMySqlWriteFails() {
        Fixtures fixtures = fixtures(DataMigrationProperties.AlarmWriteMode.MYSQL_PRIMARY);
        when(fixtures.projection.projectPrimary(any(), any())).thenThrow(new IllegalStateException("mysql down"));

        assertThatThrownBy(() -> fixtures.service.prepare(fixtures.event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql down");

        verify(fixtures.store, never()).record(any(), any(), any());
    }

    @Test
    void mysqlPrimaryKeepsMySqlFactWhenRedisCompatibilityWriteFails() {
        Fixtures fixtures = fixtures(DataMigrationProperties.AlarmWriteMode.MYSQL_PRIMARY);
        when(fixtures.projection.projectPrimary(any(), any())).thenReturn(fixtures.primaryState);
        when(fixtures.store.record(any(), any(), any())).thenThrow(new IllegalStateException("redis down"));

        AlarmEventPreparationService.PreparedAlarm prepared = fixtures.service.prepare(fixtures.event);

        assertThat(prepared.activeState()).isEqualTo(fixtures.primaryState);
        verify(fixtures.projection).projectPrimary(any(), any());
    }

    @Test
    void redisPrimaryRetainsRedisThenBestEffortProjectionOrder() {
        Fixtures fixtures = fixtures(DataMigrationProperties.AlarmWriteMode.REDIS_PRIMARY);
        when(fixtures.store.record(any(), any(), any())).thenReturn(fixtures.redisState);

        fixtures.service.prepare(fixtures.event);

        InOrder order = inOrder(fixtures.store, fixtures.projection);
        order.verify(fixtures.store).record(any(), any(), any());
        order.verify(fixtures.projection).project(any(), any(), any());
        verify(fixtures.projection, never()).projectPrimary(any(), any());
    }

    private static Fixtures fixtures(DataMigrationProperties.AlarmWriteMode mode) {
        AlarmFingerprintService fingerprint = mock(AlarmFingerprintService.class);
        AlarmPolicyEngine policy = mock(AlarmPolicyEngine.class);
        ActiveAlarmStore store = mock(ActiveAlarmStore.class);
        AlarmIncidentProjection projection = mock(AlarmIncidentProjection.class);
        NormalizedAlarmEvent event = event();
        AlarmEvaluationResult evaluation = AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "test");
        when(fingerprint.fingerprint(any())).thenReturn("fp-write-mode");
        when(policy.evaluate(any())).thenReturn(evaluation);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setAlarmWriteMode(mode);
        ActiveAlarmState primary = state("primary");
        ActiveAlarmState redis = state("redis");
        return new Fixtures(
                new AlarmEventPreparationService(fingerprint, policy, store, projection, properties),
                store,
                projection,
                event,
                primary,
                redis);
    }

    private static NormalizedAlarmEvent event() {
        return new NormalizedAlarmEvent(
                "am-write-mode",
                null,
                "NodeCpuHigh",
                "alertmanager",
                "warning",
                AlarmSeverity.P2,
                AlarmResourceType.NODE,
                "worker-01",
                "prod",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                Instant.parse("2026-07-22T00:00:00Z"),
                "CPU high",
                Map.of());
    }

    private static ActiveAlarmState state(String suffix) {
        return new ActiveAlarmState(
                "fp-write-mode-" + suffix,
                "am-write-mode",
                "NodeCpuHigh",
                "prod",
                null,
                null,
                "worker-01",
                AlarmSeverity.P2,
                AlarmStatus.FIRING,
                null,
                Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-22T00:00:00Z"),
                1L);
    }

    private record Fixtures(
            AlarmEventPreparationService service,
            ActiveAlarmStore store,
            AlarmIncidentProjection projection,
            NormalizedAlarmEvent event,
            ActiveAlarmState primaryState,
            ActiveAlarmState redisState) {}
}
