package com.kubeoncall.web.api.v1.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        when(projection.countActiveBySeverity()).thenReturn(Map.of("P1", 2L, "P2", 3L));
        when(projection.countByStatus()).thenReturn(Map.of("FIRING", 4L, "ACKNOWLEDGED", 1L, "RESOLVED", 2L));

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM koc_approval_request WHERE status = 'PENDING' AND deleted_at IS NULL",
                        Long.class))
                .thenReturn(7L);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_async_task WHERE status = 'RUNNING'", Long.class))
                .thenReturn(3L);
        when(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM koc_async_task WHERE status IN ('FAILED','DEAD_LETTER')", Long.class))
                .thenReturn(2L);

        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider = mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(jdbcTemplate);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmIncidentProjection> projProvider = mock(ObjectProvider.class);
        when(projProvider.getIfAvailable()).thenReturn(projection);

        OverviewQueryService service = new OverviewQueryService(jdbcProvider, projProvider);
        OverviewQueryService.Overview overview = service.build();

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
