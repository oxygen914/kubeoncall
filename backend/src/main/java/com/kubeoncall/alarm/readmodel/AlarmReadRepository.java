package com.kubeoncall.alarm.readmodel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MySQL-backed read model for alarms. Only active when {@code kubeoncall.mysql-enabled=true}. All
 * queries are explicit SQL so the list/detail/timeline paths stay free of N+1: the list query
 * selects a single page from a covering index, detail is a primary-key lookup, and timeline is one
 * ordered fetch keyed by incident id. JSON columns are decoded here so callers receive typed maps.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class AlarmReadRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private static final String SELECT_INCIDENT_COLUMNS = """
            id, public_id, fingerprint, cycle_no, alert_name, severity, severity_rank, status,
            resource_type, resource_name, cluster_name, namespace_name, service_name, metric_name,
            current_value, threshold_value, unit, labels_json, annotations_json, first_seen,
            last_seen, resolved_at, occurrence_count, acknowledged, acknowledged_by, acknowledged_at,
            policy_public_id,
            (SELECT e.public_id FROM koc_workflow_execution e WHERE e.id = latest_execution_id)
              AS latest_execution_public_id,
            (SELECT e.status FROM koc_workflow_execution e WHERE e.id = latest_execution_id)
              AS latest_execution_status,
            version
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public AlarmReadRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    /** Paged list ordered by {@code sort} (default {@code -lastSeen}). Returns the row plus total. */
    public AlarmListPage list(AlarmListQuery query) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE deleted_at IS NULL");
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            where.append(" AND status IN (")
                    .append(placeholders(query.statuses().size()))
                    .append(")");
            args.addAll(query.statuses());
        }
        if (query.severities() != null && !query.severities().isEmpty()) {
            where.append(" AND severity IN (")
                    .append(placeholders(query.severities().size()))
                    .append(")");
            args.addAll(query.severities());
        }
        if (isNotBlank(query.cluster())) {
            where.append(" AND cluster_name = ?");
            args.add(query.cluster());
        }
        if (isNotBlank(query.namespace())) {
            where.append(" AND namespace_name = ?");
            args.add(query.namespace());
        }
        if (isNotBlank(query.service())) {
            where.append(" AND service_name = ?");
            args.add(query.service());
        }
        if (isNotBlank(query.q())) {
            where.append(" AND (alert_name LIKE ? OR resource_name LIKE ?)");
            String like = "%" + query.q() + "%";
            args.add(like);
            args.add(like);
        }

        String order = resolveSort(query.sort());
        long total = count(where.toString(), args);
        int offset = Math.max(0, (query.page() - 1) * query.size());

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_INCIDENT_COLUMNS)
                .append(" FROM koc_alarm_incident ")
                .append(where)
                .append(" ORDER BY ")
                .append(order)
                .append(" LIMIT ? OFFSET ?");
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(query.size());
        pagedArgs.add(offset);

        List<AlarmIncidentRecord> rows =
                jdbcTemplate.query(sql.toString(), new IncidentRowMapper(objectMapper), pagedArgs.toArray());
        return new AlarmListPage(rows, total);
    }

    public Optional<AlarmIncidentRecord> findByPublicId(String publicId) {
        try {
            AlarmIncidentRecord row = jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_INCIDENT_COLUMNS
                            + " FROM koc_alarm_incident WHERE public_id = ? AND deleted_at IS NULL",
                    new IncidentRowMapper(objectMapper),
                    publicId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Returns the newest non-deleted incident for a Redis alarm fingerprint. */
    public Optional<AlarmIncidentRecord> findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        try {
            AlarmIncidentRecord row = jdbcTemplate.queryForObject(
                    "SELECT " + SELECT_INCIDENT_COLUMNS
                            + " FROM koc_alarm_incident WHERE fingerprint = ? AND deleted_at IS NULL"
                            + " ORDER BY cycle_no DESC, id DESC LIMIT 1",
                    new IncidentRowMapper(objectMapper),
                    fingerprint.trim());
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Returns bounded alarm facts observed in the same evidence window as an AI execution. */
    public List<AlarmIncidentRecord> findBetween(
            Instant from, Instant to, String cluster, String namespace, String resourceName, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_INCIDENT_COLUMNS)
                .append(" FROM koc_alarm_incident WHERE deleted_at IS NULL")
                .append(" AND last_seen BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(from == null ? Instant.EPOCH : from));
        args.add(Timestamp.from(to == null ? Instant.now() : to));
        if (isNotBlank(cluster)) {
            sql.append(" AND cluster_name = ?");
            args.add(cluster);
        }
        if (isNotBlank(namespace)) {
            sql.append(" AND namespace_name = ?");
            args.add(namespace);
        }
        if (isNotBlank(resourceName)) {
            sql.append(" AND resource_name = ?");
            args.add(resourceName);
        }
        sql.append(" ORDER BY last_seen DESC, id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 100)));
        return jdbcTemplate.query(sql.toString(), new IncidentRowMapper(objectMapper), args.toArray());
    }

    public List<AlarmTimelineItem> timeline(String publicId, int limit, Instant after) {
        Optional<Long> incidentId = findIdByPublicId(publicId);
        if (incidentId.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT public_id, to_status, reason_code, reason, actor_type, actor_id, request_id, occurred_at
                  FROM koc_alarm_status_history
                 WHERE incident_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(incidentId.get());
        if (after != null) {
            sql.append(" AND occurred_at > ?");
            args.add(Timestamp.from(after));
        }
        sql.append(" ORDER BY occurred_at ASC, id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> {
                    String fromStatus = null;
                    String toStatus = rs.getString("to_status");
                    String type = timelineType(toStatus, rs.getString("reason_code"));
                    return new AlarmTimelineItem(
                            rs.getString("public_id"),
                            type,
                            toInstant(rs.getTimestamp("occurred_at")),
                            new AlarmTimelineItem.Actor(rs.getString("actor_type"), null, null),
                            rs.getString("reason"),
                            Map.of("toStatus", nullable(toStatus), "reasonCode", nullable(rs.getString("reason_code"))),
                            rs.getString("request_id"));
                },
                args.toArray());
    }

    public Optional<Long> findIdByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id FROM koc_alarm_incident WHERE public_id = ? AND deleted_at IS NULL",
                    Long.class,
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private long count(String where, List<Object> args) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_incident " + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private static String timelineType(String toStatus, String reasonCode) {
        if (reasonCode != null && !reasonCode.isBlank()) {
            return reasonCode;
        }
        return switch (toStatus == null ? "" : toStatus) {
            case "FIRING" -> "ALARM_FIRING";
            case "ACKNOWLEDGED" -> "ALARM_ACKNOWLEDGED";
            case "RECOVERY_PENDING" -> "RECOVERY_CANDIDATE";
            case "RESOLVED" -> "ALARM_RESOLVED";
            case "SUPPRESSED" -> "ALARM_SUPPRESSED";
            default -> "ALARM_EVENT";
        };
    }

    private static String resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "last_seen DESC, id DESC";
        }
        String trimmed = sort.trim();
        boolean desc = trimmed.startsWith("-");
        String field = desc ? trimmed.substring(1) : trimmed;
        return switch (field) {
            case "lastSeen" -> "last_seen " + (desc ? "DESC" : "ASC") + ", id DESC";
            case "firstSeen" -> "first_seen " + (desc ? "DESC" : "ASC") + ", id DESC";
            case "severity" -> "severity_rank ASC, last_seen DESC";
            case "occurrenceCount" -> "occurrence_count " + (desc ? "DESC" : "ASC") + ", id DESC";
            default -> "last_seen DESC, id DESC";
        };
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** Query parameters for the alarm list endpoint. {@code page} is 1-based. */
    public record AlarmListQuery(
            int page,
            int size,
            String sort,
            List<String> statuses,
            List<String> severities,
            String cluster,
            String namespace,
            String service,
            String q) {}

    public record AlarmListPage(List<AlarmIncidentRecord> rows, long total) {}

    private static final class IncidentRowMapper implements RowMapper<AlarmIncidentRecord> {

        private final ObjectMapper objectMapper;

        IncidentRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public AlarmIncidentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AlarmIncidentRecord(
                    rs.getLong("id"),
                    rs.getString("public_id"),
                    rs.getString("fingerprint"),
                    rs.getInt("cycle_no"),
                    rs.getString("alert_name"),
                    rs.getString("severity"),
                    rs.getInt("severity_rank"),
                    rs.getString("status"),
                    rs.getString("resource_type"),
                    rs.getString("resource_name"),
                    rs.getString("cluster_name"),
                    rs.getString("namespace_name"),
                    rs.getString("service_name"),
                    rs.getString("metric_name"),
                    rs.getObject("current_value") == null ? null : rs.getDouble("current_value"),
                    rs.getObject("threshold_value") == null ? null : rs.getDouble("threshold_value"),
                    rs.getString("unit"),
                    decodeStringMap(rs.getString("labels_json")),
                    decodeStringMap(rs.getString("annotations_json")),
                    toInstant(rs.getTimestamp("first_seen")),
                    toInstant(rs.getTimestamp("last_seen")),
                    toInstant(rs.getTimestamp("resolved_at")),
                    rs.getLong("occurrence_count"),
                    rs.getBoolean("acknowledged"),
                    rs.getObject("acknowledged_by") == null ? null : rs.getLong("acknowledged_by"),
                    toInstant(rs.getTimestamp("acknowledged_at")),
                    rs.getString("policy_public_id"),
                    rs.getString("latest_execution_public_id"),
                    rs.getString("latest_execution_status"),
                    rs.getLong("version"));
        }

        private Map<String, String> decodeStringMap(String json) {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            try {
                Map<String, String> decoded = objectMapper.readValue(json, STRING_MAP);
                return decoded == null ? Map.of() : new LinkedHashMap<>(decoded);
            } catch (Exception ex) {
                return Map.of();
            }
        }
    }

    /** Unused but kept so future timeline payloads can decode JSON details uniformly. */
    @SuppressWarnings("unused")
    private Map<String, Object> decodeObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> decoded = objectMapper.readValue(json, OBJECT_MAP);
            return decoded == null ? Map.of() : new LinkedHashMap<>(decoded);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
