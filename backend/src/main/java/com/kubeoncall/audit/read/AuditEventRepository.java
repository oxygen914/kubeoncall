package com.kubeoncall.audit.read;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit-SQL, read-only access to the append-only operation-audit facts. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class AuditEventRepository {

    private static final int MAX_PAGE_SIZE = 100;
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String FROM = """
            FROM koc_operation_audit a
            LEFT JOIN koc_user u ON u.id = a.actor_id
            """;
    private static final String COLUMNS = """
            a.public_id, a.actor_type, u.public_id AS actor_public_id,
            a.actor_display_name, a.action, a.resource_type, a.resource_public_id,
            a.result, a.reason, a.before_json, a.after_json, a.request_id, a.trace_id,
            INET6_NTOA(a.source_ip) AS source_ip, a.user_agent, a.occurred_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public AuditEventRepository(
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

    public AuditPage list(AuditQuery query) {
        AuditQuery safeQuery = query == null ? AuditQuery.empty() : query;
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendActor(where, args, safeQuery.actor());
        appendEquals(where, args, "a.action", safeQuery.action());
        appendEquals(where, args, "a.resource_type", safeQuery.resourceType());
        appendEquals(where, args, "a.resource_public_id", safeQuery.resourceId());
        appendEquals(where, args, "a.result", safeQuery.result());
        appendEquals(where, args, "a.request_id", safeQuery.requestId());
        appendFrom(where, args, safeQuery.from());
        appendTo(where, args, safeQuery.to());

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) " + FROM + where, Long.class, args.toArray());
        int page = Math.max(1, safeQuery.page());
        int size = Math.max(1, Math.min(safeQuery.size(), MAX_PAGE_SIZE));
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<AuditEventRecord> events = jdbcTemplate.query(
                "SELECT " + COLUMNS + FROM + where + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT ? OFFSET ?",
                new AuditRowMapper(objectMapper),
                pageArgs.toArray());
        return new AuditPage(events, count == null ? 0 : count);
    }

    public Optional<AuditEventRecord> find(String auditPublicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS + FROM + " WHERE a.public_id = ?",
                    new AuditRowMapper(objectMapper),
                    auditPublicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static void appendActor(StringBuilder where, List<Object> args, String actor) {
        if (!hasText(actor)) {
            return;
        }
        String value = actor.trim();
        where.append(" AND (a.actor_type = ? OR u.public_id = ? OR a.actor_display_name LIKE CONCAT('%', ?, '%'))");
        args.add(value);
        args.add(value);
        args.add(value);
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (hasText(value)) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private static void appendFrom(StringBuilder where, List<Object> args, Instant from) {
        if (from != null) {
            where.append(" AND a.occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
    }

    private static void appendTo(StringBuilder where, List<Object> args, Instant to) {
        if (to != null) {
            where.append(" AND a.occurred_at <= ?");
            args.add(Timestamp.from(to));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Map<String, Object> readMap(ObjectMapper objectMapper, String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new SQLException("Invalid JSON persisted for operation audit", ex);
        }
    }

    private static final class AuditRowMapper implements RowMapper<AuditEventRecord> {

        private final ObjectMapper objectMapper;

        private AuditRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public AuditEventRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            return new AuditEventRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("actor_type"),
                    resultSet.getString("actor_public_id"),
                    resultSet.getString("actor_display_name"),
                    resultSet.getString("action"),
                    resultSet.getString("resource_type"),
                    resultSet.getString("resource_public_id"),
                    resultSet.getString("result"),
                    resultSet.getString("reason"),
                    readMap(objectMapper, resultSet.getString("before_json")),
                    readMap(objectMapper, resultSet.getString("after_json")),
                    resultSet.getString("request_id"),
                    resultSet.getString("trace_id"),
                    resultSet.getString("source_ip"),
                    resultSet.getString("user_agent"),
                    instant(resultSet, "occurred_at"));
        }
    }

    public record AuditQuery(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String requestId,
            Instant from,
            Instant to,
            int page,
            int size) {

        public static AuditQuery empty() {
            return new AuditQuery(null, null, null, null, null, null, null, null, 1, 20);
        }
    }

    public record AuditPage(List<AuditEventRecord> items, long total) {

        public AuditPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
