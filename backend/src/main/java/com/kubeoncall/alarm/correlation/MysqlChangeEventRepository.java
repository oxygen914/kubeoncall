package com.kubeoncall.alarm.correlation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MySQL-backed {@link ChangeEventRepository} (WBS-11 GAP-11-01). Only active when
 * {@code kubeoncall.mysql-enabled=true}. {@code change_id} is the natural idempotency key:
 * {@code saveIfAbsent} inserts and treats a duplicate as "already present". Reads hit the
 * {@code (cluster, namespace, occurred_at)} covering index so the correlation window is an index
 * scan, not the in-memory filter the Redis projection relied on.
 *
 * <p>This repository is intentionally <strong>not</strong> {@code @Primary}: {@link ChangeCorrelationService}
 * routes between Redis and MySQL per the configured read source, so both beans coexist during the
 * cutover. It is also the backfill target for {@code ChangeEventBackfillRunner}.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MysqlChangeEventRepository implements ChangeEventRepository {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private static final String INSERT_SQL = """
            INSERT INTO koc_change_event
              (public_id, change_id, change_type, changed_by, occurred_at, resource_type, resource_name,
               namespace, cluster, diff_payload, change_source, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String EXISTS_BY_CHANGE_ID_SQL =
            "SELECT COUNT(*) FROM koc_change_event WHERE change_id = ? AND deleted_at IS NULL";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean mysqlEnabled;

    public MysqlChangeEventRepository(
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

    /** Returns {@code true} only when this change id is not yet persisted as a live fact. */
    public boolean existsByChangeId(String changeId) {
        Integer count = jdbcTemplate.queryForObject(EXISTS_BY_CHANGE_ID_SQL, Integer.class, changeId);
        return count != null && count > 0;
    }

    @Override
    public void save(ChangeEvent event) {
        saveIfAbsent(event);
    }

    @Override
    public boolean saveIfAbsent(ChangeEvent event) {
        if (event == null || event.changeId() == null || event.changeId().isBlank()) {
            return false;
        }
        try {
            int rows = jdbcTemplate.update(
                    INSERT_SQL,
                    "cevt_" + UUID.randomUUID().toString().replace("-", ""),
                    event.changeId(),
                    event.changeType(),
                    event.changedBy(),
                    Timestamp.from(event.changedAt()),
                    event.resourceType(),
                    event.resourceName(),
                    event.namespace(),
                    event.cluster(),
                    encodeDiff(event.diff()),
                    event.changeSource(),
                    event.correlationId());
            return rows > 0;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public List<ChangeEvent> findBetween(Instant from, Instant to, String cluster, String namespace) {
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? Instant.now() : to;
        StringBuilder sql = new StringBuilder("""
                SELECT change_id, change_type, changed_by, occurred_at, resource_type, resource_name,
                       namespace, cluster, diff_payload, change_source, correlation_id
                  FROM koc_change_event
                 WHERE deleted_at IS NULL AND occurred_at BETWEEN ? AND ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(start));
        args.add(Timestamp.from(end));
        if (isPresent(cluster)) {
            sql.append(" AND cluster = ?");
            args.add(cluster);
        }
        if (isPresent(namespace)) {
            sql.append(" AND namespace = ?");
            args.add(namespace);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT 500");
        return jdbcTemplate.query(sql.toString(), new ChangeEventRowMapper(objectMapper), args.toArray());
    }

    private String encodeDiff(Map<String, Object> diff) {
        if (diff == null || diff.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(diff);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Finds one live change event by its idempotency key, for diff verification. */
    public Optional<ChangeEvent> findByChangeId(String changeId) {
        if (changeId == null || changeId.isBlank()) {
            return Optional.empty();
        }
        try {
            ChangeEvent event = jdbcTemplate.queryForObject("""
                    SELECT change_id, change_type, changed_by, occurred_at, resource_type, resource_name,
                           namespace, cluster, diff_payload, change_source, correlation_id
                      FROM koc_change_event
                     WHERE change_id = ? AND deleted_at IS NULL
                    """, new ChangeEventRowMapper(objectMapper), changeId);
            return Optional.ofNullable(event);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static final class ChangeEventRowMapper implements RowMapper<ChangeEvent> {

        private final ObjectMapper objectMapper;

        ChangeEventRowMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ChangeEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChangeEvent(
                    rs.getString("change_id"),
                    rs.getString("change_type"),
                    rs.getString("changed_by"),
                    toInstant(rs.getTimestamp("occurred_at")),
                    rs.getString("resource_type"),
                    rs.getString("resource_name"),
                    rs.getString("namespace"),
                    rs.getString("cluster"),
                    decodeObjectMap(rs.getString("diff_payload")),
                    rs.getString("change_source"),
                    rs.getString("correlation_id"));
        }

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

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
