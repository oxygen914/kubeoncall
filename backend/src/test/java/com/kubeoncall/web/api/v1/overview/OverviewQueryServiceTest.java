package com.kubeoncall.web.api.v1.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;

class OverviewQueryServiceTest {

    @Test
    void buildAggregatesAlarmApprovalAndExecutionCounts() {
        AlarmIncidentProjection projection = mock(AlarmIncidentProjection.class);
        when(projection.isAvailable()).thenReturn(true);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Timestamp.class)))
                .thenReturn(
                        List.of(Map.of("label", "P1", "total", 2L), Map.of("label", "P2", "total", 3L)),
                        List.of(
                                Map.of("label", "FIRING", "total", 4L),
                                Map.of("label", "ACKNOWLEDGED", "total", 1L),
                                Map.of("label", "RESOLVED", "total", 2L)));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Timestamp.class)))
                .thenReturn(7L, 3L, 2L);

        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmIncidentProjection> projProvider = mock(ObjectProvider.class);
        when(projProvider.getIfAvailable()).thenReturn(projection);

        OverviewQueryService service = new OverviewQueryService(jdbcProvider, projProvider);
        OverviewQueryService.Overview overview = service.build(Instant.parse("2026-07-20T00:00:00Z"));

        assertThat(overview.activeAlarms()).isEqualTo(5L); // FIRING + ACKNOWLEDGED
        assertThat(overview.pendingApprovals()).isEqualTo(7L);
        assertThat(overview.runningExecutions()).isEqualTo(3L);
        assertThat(overview.failedExecutions()).isEqualTo(2L);
        assertThat(overview.severityCounts()).containsEntry("P1", 2L).containsEntry("P2", 3L);
    }

    @Test
    void unavailableWhenMySqlDisabled() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmIncidentProjection> projProvider = mock(ObjectProvider.class);
        OverviewQueryService service = new OverviewQueryService(jdbcProvider, projProvider);
        assertThat(service.isAvailable()).isFalse();
    }
}
