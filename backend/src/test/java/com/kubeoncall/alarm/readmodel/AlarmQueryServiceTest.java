package com.kubeoncall.alarm.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AlarmQueryServiceTest {

    @Test
    void listAndDetailExposeLatestWorkflowExecutionProjection() {
        AlarmReadRepository repository = mock(AlarmReadRepository.class);
        when(repository.isAvailable()).thenReturn(true);
        AlarmIncidentRecord incident = incidentWithLatestExecution();
        when(repository.list(any())).thenReturn(new AlarmReadRepository.AlarmListPage(List.of(incident), 1L));
        when(repository.findByPublicId("alm_1")).thenReturn(Optional.of(incident));
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmReadRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.kubeoncall.alarm.state.ActiveAlarmStore> storeProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.kubeoncall.migration.MigrationLedgerRepository> ledgerProvider = mock(ObjectProvider.class);
        com.kubeoncall.common.config.KubeOnCallProperties properties =
                new com.kubeoncall.common.config.KubeOnCallProperties();
        AlarmQueryService service = new AlarmQueryService(provider, storeProvider, ledgerProvider, properties);

        AlarmQueryService.AlarmListItem listItem = service.list(
                        new AlarmQueryService.AlarmListRequest(1, 20, null, null, null, null, null, null, null))
                .items()
                .get(0);
        AlarmQueryService.AlarmDetail detail = service.detail("alm_1").orElseThrow();

        assertThat(listItem.latestExecution()).isNotNull();
        assertThat(listItem.latestExecution().id()).isEqualTo("exe_alarm_1");
        assertThat(listItem.latestExecution().status()).isEqualTo("SUCCEEDED");
        assertThat(detail.latestExecution()).isEqualTo(listItem.latestExecution());
    }

    private static AlarmIncidentRecord incidentWithLatestExecution() {
        Instant observedAt = Instant.parse("2026-07-20T08:00:00Z");
        return new AlarmIncidentRecord(
                1L,
                "alm_1",
                "fp_1",
                1,
                "NodeCpuHigh",
                "P1",
                1,
                "FIRING",
                "NODE",
                "worker-01",
                "prod",
                null,
                null,
                "cpu_usage",
                95.0,
                90.0,
                "%",
                Map.of(),
                Map.of(),
                observedAt,
                observedAt,
                null,
                1L,
                false,
                null,
                null,
                null,
                "exe_alarm_1",
                "SUCCEEDED",
                1L);
    }
}
