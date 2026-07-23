package com.kubeoncall.knowledge.mysql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Explicit-SQL lifecycle repository for JSONL/file knowledge imports. */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class KnowledgeImportRepository {

    private static final String COLUMNS = """
            i.public_id, t.public_id AS task_public_id, i.import_type, i.duplicate_policy,
            i.dry_run, i.status, i.source_bucket, i.source_object_key, i.source_checksum,
            i.source_size_bytes, i.dataset_version, i.total_count, i.processed_count,
            i.succeeded_count, i.failed_count, i.skipped_count, i.error_report_bucket,
            i.error_report_object_key, i.error_code, i.error_summary, i.started_at,
            i.finished_at, i.version, i.created_at, i.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean mysqlEnabled;

    public KnowledgeImportRepository(
            JdbcTemplate jdbcTemplate, @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    public KnowledgeImportRecord create(CreateImport command) {
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM koc_async_task WHERE public_id = ?", Long.class, command.taskPublicId());
        if (taskId == null) {
            throw new IllegalArgumentException("Unknown async task: " + command.taskPublicId());
        }
        String publicId = publicId(command.publicId());
        jdbcTemplate.update(
                """
                INSERT INTO koc_knowledge_import
                  (public_id, task_id, import_type, duplicate_policy, dry_run, status,
                   source_bucket, source_object_key, source_checksum, source_size_bytes,
                   dataset_version, total_count)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                """,
                publicId,
                taskId,
                command.importType(),
                defaultValue(command.duplicatePolicy(), "SKIP"),
                command.dryRun(),
                command.sourceBucket(),
                command.sourceObjectKey(),
                checksum(command.sourceChecksum()),
                command.sourceSizeBytes(),
                command.datasetVersion(),
                command.totalCount());
        return find(publicId)
                .orElseThrow(() -> new IllegalStateException("Created knowledge import is not readable: " + publicId));
    }

    public Optional<KnowledgeImportRecord> find(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + COLUMNS
                            + " FROM koc_knowledge_import i"
                            + " JOIN koc_async_task t ON t.id = i.task_id"
                            + " WHERE i.public_id = ?",
                    new ImportRowMapper(),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public ImportPage list(ImportQuery query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendEquals(where, args, "i.status", query.status());
        appendEquals(where, args, "i.import_type", query.importType());
        appendEquals(where, args, "i.dataset_version", query.datasetVersion());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_knowledge_import i" + where, Long.class, args.toArray());
        int page = Math.max(1, query.page());
        int size = Math.max(1, Math.min(query.size(), 200));
        List<Object> pagedArgs = new ArrayList<>(args);
        pagedArgs.add(size);
        pagedArgs.add((page - 1) * size);
        List<KnowledgeImportRecord> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM koc_knowledge_import i"
                        + " JOIN koc_async_task t ON t.id = i.task_id"
                        + where
                        + " ORDER BY i.created_at DESC, i.id DESC LIMIT ? OFFSET ?",
                new ImportRowMapper(),
                pagedArgs.toArray());
        return new ImportPage(rows, count == null ? 0 : count);
    }

    /** Object keys that are still reachable from durable import metadata or error reports. */
    public Set<String> referencedObjectKeys(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return Set.of();
        }
        List<String> keys = jdbcTemplate.query("""
                SELECT source_object_key AS object_key
                  FROM koc_knowledge_import
                 WHERE source_bucket = ? AND source_object_key IS NOT NULL
                UNION
                SELECT error_report_object_key AS object_key
                  FROM koc_knowledge_import
                 WHERE error_report_bucket = ? AND error_report_object_key IS NOT NULL
                """, (rs, rowNum) -> rs.getString("object_key"), bucket, bucket);
        return keys.stream()
                .filter(key -> key != null && !key.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean updateProgress(
            String publicId,
            long expectedVersion,
            Long totalCount,
            long processedCount,
            long succeededCount,
            long failedCount,
            long skippedCount,
            Instant startedAt) {
        validateCounts(totalCount, processedCount, succeededCount, failedCount, skippedCount);
        return jdbcTemplate.update(
                        """
                        UPDATE koc_knowledge_import
                           SET status = 'RUNNING', total_count = COALESCE(?, total_count),
                               processed_count = ?, succeeded_count = ?, failed_count = ?,
                               skipped_count = ?, started_at = COALESCE(started_at, ?),
                               version = version + 1
                         WHERE public_id = ? AND version = ?
                           AND status IN ('PENDING', 'RUNNING')
                        """,
                        totalCount,
                        processedCount,
                        succeededCount,
                        failedCount,
                        skippedCount,
                        startedAt,
                        publicId,
                        expectedVersion)
                == 1;
    }

    public boolean complete(
            String publicId,
            long expectedVersion,
            long processedCount,
            long succeededCount,
            long failedCount,
            long skippedCount,
            String errorReportBucket,
            String errorReportObjectKey,
            Instant finishedAt) {
        validateCounts(null, processedCount, succeededCount, failedCount, skippedCount);
        String status = failedCount > 0 ? "PARTIAL" : "SUCCEEDED";
        return jdbcTemplate.update(
                        """
                        UPDATE koc_knowledge_import
                           SET status = ?, processed_count = ?, succeeded_count = ?,
                               failed_count = ?, skipped_count = ?, error_report_bucket = ?,
                               error_report_object_key = ?, finished_at = ?,
                               version = version + 1
                         WHERE public_id = ? AND version = ?
                           AND status IN ('PENDING', 'RUNNING')
                        """,
                        status,
                        processedCount,
                        succeededCount,
                        failedCount,
                        skippedCount,
                        errorReportBucket,
                        errorReportObjectKey,
                        finishedAt,
                        publicId,
                        expectedVersion)
                == 1;
    }

    public boolean fail(
            String publicId,
            long expectedVersion,
            String errorCode,
            String errorSummary,
            String errorReportBucket,
            String errorReportObjectKey,
            Instant finishedAt) {
        return jdbcTemplate.update(
                        """
                        UPDATE koc_knowledge_import
                           SET status = 'FAILED', error_code = ?, error_summary = ?,
                               error_report_bucket = ?, error_report_object_key = ?,
                               finished_at = ?, version = version + 1
                         WHERE public_id = ? AND version = ?
                           AND status IN ('PENDING', 'RUNNING')
                        """,
                        errorCode,
                        errorSummary,
                        errorReportBucket,
                        errorReportObjectKey,
                        finishedAt,
                        publicId,
                        expectedVersion)
                == 1;
    }

    private static void validateCounts(
            Long totalCount, long processedCount, long succeededCount, long failedCount, long skippedCount) {
        if (processedCount < 0
                || succeededCount < 0
                || failedCount < 0
                || skippedCount < 0
                || processedCount != succeededCount + failedCount + skippedCount
                || (totalCount != null && processedCount > totalCount)) {
            throw new IllegalArgumentException("Import counters are inconsistent");
        }
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static byte[] checksum(String hex) {
        if (hex == null || hex.length() != 64) {
            throw new IllegalArgumentException("Checksum must be a 64-character SHA-256 hex value");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Checksum must be hexadecimal", ex);
        }
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "imp_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column) == null
                ? null
                : resultSet.getTimestamp(column).toInstant();
    }

    private static final class ImportRowMapper implements RowMapper<KnowledgeImportRecord> {

        @Override
        public KnowledgeImportRecord mapRow(ResultSet resultSet, int rowNum) throws SQLException {
            byte[] digest = resultSet.getBytes("source_checksum");
            long total = resultSet.getLong("total_count");
            boolean totalWasNull = resultSet.wasNull();
            return new KnowledgeImportRecord(
                    resultSet.getString("public_id"),
                    resultSet.getString("task_public_id"),
                    resultSet.getString("import_type"),
                    resultSet.getString("duplicate_policy"),
                    resultSet.getBoolean("dry_run"),
                    resultSet.getString("status"),
                    resultSet.getString("source_bucket"),
                    resultSet.getString("source_object_key"),
                    digest == null ? null : HexFormat.of().formatHex(digest),
                    resultSet.getLong("source_size_bytes"),
                    resultSet.getString("dataset_version"),
                    totalWasNull ? null : total,
                    resultSet.getLong("processed_count"),
                    resultSet.getLong("succeeded_count"),
                    resultSet.getLong("failed_count"),
                    resultSet.getLong("skipped_count"),
                    resultSet.getString("error_report_bucket"),
                    resultSet.getString("error_report_object_key"),
                    resultSet.getString("error_code"),
                    resultSet.getString("error_summary"),
                    instant(resultSet, "started_at"),
                    instant(resultSet, "finished_at"),
                    resultSet.getLong("version"),
                    instant(resultSet, "created_at"),
                    instant(resultSet, "updated_at"));
        }
    }

    public record CreateImport(
            String publicId,
            String taskPublicId,
            String importType,
            String duplicatePolicy,
            boolean dryRun,
            String sourceBucket,
            String sourceObjectKey,
            String sourceChecksum,
            long sourceSizeBytes,
            String datasetVersion,
            Long totalCount) {}

    public record ImportQuery(String status, String importType, String datasetVersion, int page, int size) {}

    public record ImportPage(List<KnowledgeImportRecord> items, long total) {}
}
