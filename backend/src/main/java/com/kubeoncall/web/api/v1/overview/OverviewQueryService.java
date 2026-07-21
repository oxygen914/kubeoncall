package com.kubeoncall.web.api.v1.overview;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;

/**
 * Aggregates the Dashboard overview from the MySQL read models (WBS-5 §4 Overview). A single query
 * per domain — no N+1, no external probes — so the dashboard stays fast and never blocks on Redis or
 * the tool services. When MySQL is disabled the service reports unavailable and the controller
 * returns 503, so the frontend never mistakes a missing deployment for an empty dashboard.
 */
@Service
public class OverviewQueryService {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectProvider<AlarmIncidentProjection> alarmProjectionProvider;

    public OverviewQueryService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<AlarmIncidentProjection> alarmProjectionProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.alarmProjectionProvider = alarmProjectionProvider;
    }

    public boolean isAvailable() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        AlarmIncidentProjection projection = alarmProjectionProvider.getIfAvailable();
        return jdbcTemplate != null && projection != null && projection.isAvailable();
    }

    public Overview build() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        AlarmIncidentProjection projection = alarmProjectionProvider.getIfAvailable();
        Map<String, Long> severityCounts = projection == null ? Map.of() : projection.countActiveBySeverity();
        Map<String, Long> statusCounts = projection == null ? Map.of() : projection.countByStatus();

        long activeAlarms = statusCounts.getOrDefault("FIRING", 0L) + statusCounts.getOrDefault("ACKNOWLEDGED", 0L);
        long pendingApprovals = count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM koc_approval_request WHERE status = 'PENDING' AND deleted_at IS NULL");
        long runningExecutions = count(jdbcTemplate, "SELECT COUNT(*) FROM koc_async_task WHERE status = 'RUNNING'");
        long failedExecutions =
                count(jdbcTemplate, "SELECT COUNT(*) FROM koc_async_task WHERE status IN ('FAILED','DEAD_LETTER')");

        return new Overview(
                activeAlarms, pendingApprovals, runningExecutions, failedExecutions, severityCounts, statusCounts);
    }

    private static long count(JdbcTemplate jdbcTemplate, String sql) {
        if (jdbcTemplate == null) {
            return 0;
        }
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (Exception ex) {
            return 0;
        }
    }

    public record Overview(
            long activeAlarms,
            long pendingApprovals,
            long runningExecutions,
            long failedExecutions,
            Map<String, Long> severityCounts,
            Map<String, Long> statusCounts) {

        public Overview {
            severityCounts = severityCounts == null ? Map.of() : new LinkedHashMap<>(severityCounts);
            statusCounts = statusCounts == null ? Map.of() : new LinkedHashMap<>(statusCounts);
        }
    }
}
