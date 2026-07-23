package com.kubeoncall.web.api.v1.overview;

import java.sql.Timestamp;
import java.time.Instant;
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

    public Overview build(Instant windowStart) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Overview database is unavailable");
        }
        Timestamp since = Timestamp.from(windowStart);
        Map<String, Long> severityCounts = countBy(jdbcTemplate, """
                SELECT severity AS label, COUNT(*) AS total FROM koc_alarm_incident
                 WHERE status IN ('FIRING', 'ACKNOWLEDGED') AND deleted_at IS NULL AND last_seen >= ?
                 GROUP BY severity
                """, since);
        Map<String, Long> statusCounts = countBy(jdbcTemplate, """
                SELECT status AS label, COUNT(*) AS total FROM koc_alarm_incident
                 WHERE deleted_at IS NULL AND last_seen >= ?
                 GROUP BY status
                """, since);
        Map<String, Long> executionStatusCounts = countBy(jdbcTemplate, """
                SELECT status AS label, COUNT(*) AS total FROM koc_workflow_execution
                 WHERE created_at >= ?
                 GROUP BY status
                """, since);
        Map<String, Long> failureReasons = countBy(jdbcTemplate, """
                SELECT COALESCE(NULLIF(error_code, ''), 'UNCLASSIFIED') AS label, COUNT(*) AS total
                  FROM koc_workflow_execution
                 WHERE status = 'FAILED' AND created_at >= ?
                 GROUP BY COALESCE(NULLIF(error_code, ''), 'UNCLASSIFIED')
                 ORDER BY total DESC, label ASC
                 LIMIT 5
                """, since);
        Map<String, Long> executionTrend = countBy(jdbcTemplate, """
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') AS label, COUNT(*) AS total
                  FROM koc_workflow_execution
                 WHERE created_at >= ?
                 GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d %H:00')
                 ORDER BY label ASC
                """, since);

        long activeAlarms = statusCounts.getOrDefault("FIRING", 0L) + statusCounts.getOrDefault("ACKNOWLEDGED", 0L);
        long pendingApprovals = count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM koc_approval_request WHERE status = 'PENDING' AND requested_at >= ?",
                since);
        long runningExecutions = count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM koc_workflow_execution WHERE status = 'RUNNING' AND created_at >= ?",
                since);
        long failedExecutions = count(
                jdbcTemplate,
                "SELECT COUNT(*) FROM koc_workflow_execution WHERE status = 'FAILED' AND created_at >= ?",
                since);

        return new Overview(
                activeAlarms,
                pendingApprovals,
                runningExecutions,
                failedExecutions,
                severityCounts,
                statusCounts,
                executionStatusCounts,
                failureReasons,
                executionTrend);
    }

    private static long count(JdbcTemplate jdbcTemplate, String sql, Timestamp since) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, since);
        return value == null ? 0 : value;
    }

    private static Map<String, Long> countBy(JdbcTemplate jdbcTemplate, String sql, Timestamp since) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, since)) {
            Object total = row.get("total");
            if (row.get("label") != null && total instanceof Number count) {
                result.put(String.valueOf(row.get("label")), count.longValue());
            }
        }
        return result;
    }

    public record Overview(
            long activeAlarms,
            long pendingApprovals,
            long runningExecutions,
            long failedExecutions,
            Map<String, Long> severityCounts,
            Map<String, Long> statusCounts,
            Map<String, Long> executionStatusCounts,
            Map<String, Long> failureReasons,
            Map<String, Long> executionTrend) {

        public Overview {
            severityCounts = severityCounts == null ? Map.of() : new LinkedHashMap<>(severityCounts);
            statusCounts = statusCounts == null ? Map.of() : new LinkedHashMap<>(statusCounts);
            executionStatusCounts =
                    executionStatusCounts == null ? Map.of() : new LinkedHashMap<>(executionStatusCounts);
            failureReasons = failureReasons == null ? Map.of() : new LinkedHashMap<>(failureReasons);
            executionTrend = executionTrend == null ? Map.of() : new LinkedHashMap<>(executionTrend);
        }
    }
}
