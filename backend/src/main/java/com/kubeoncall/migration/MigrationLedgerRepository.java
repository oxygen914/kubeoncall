package com.kubeoncall.migration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists migration batch/item/diff rows so every backfill run is observable and repeatable. Only
 * active when MySQL is enabled. All writes are best-effort: a ledger failure must never abort a
 * migration batch — the batch proceeds and the gap is visible in the next run's idempotency check.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MigrationLedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(MigrationLedgerRepository.class);

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public MigrationLedgerRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
    }

    public boolean isAvailable() {
        return jdbcTemplateProvider.getIfAvailable() != null;
    }

    public String beginBatch(String domain, String mode, String requestId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return null;
        }
        String batchId = "mbt_" + UUID.randomUUID().toString().replace("-", "");
        try {
            jdbcTemplate.update("""
                    INSERT INTO koc_migration_batch
                      (batch_id, domain, mode, status, started_at, request_id)
                    VALUES (?, ?, ?, 'RUNNING', ?, ?)
                    """, batchId, domain, mode, Timestamp.from(Instant.now()), requestId);
            return batchId;
        } catch (Exception ex) {
            log.warn("Failed to begin migration batch: {}", ex.getMessage());
            return null;
        }
    }

    public void finishBatch(String batchId, long scanned, long migrated, long skipped, long failed, String checkpoint) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || batchId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    UPDATE koc_migration_batch
                       SET status = 'COMPLETED', finished_at = ?, scanned = ?, migrated = ?,
                           skipped = ?, failed = ?, checkpoint = ?
                     WHERE batch_id = ?
                    """, Timestamp.from(Instant.now()), scanned, migrated, skipped, failed, checkpoint, batchId);
        } catch (Exception ex) {
            log.warn("Failed to finish migration batch {}: {}", batchId, ex.getMessage());
        }
    }

    public void recordItem(
            String batchId, String sourceKey, String domain, String targetPublicId, String result, String reason) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || batchId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT IGNORE INTO koc_migration_item
                      (batch_id, source_key, domain, target_public_id, result, reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, batchId, truncate(sourceKey, 512), domain, targetPublicId, result, truncate(reason, 1000));
        } catch (Exception ex) {
            log.warn("Failed to record migration item key={}: {}", sourceKey, ex.getMessage());
        }
    }

    public void recordDiff(
            String domain,
            String resourcePublicId,
            String diffType,
            String redisSummary,
            String mysqlSummary,
            String requestId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO koc_migration_diff
                      (public_id, domain, resource_public_id, diff_type, redis_summary, mysql_summary, request_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    "mdiff_" + UUID.randomUUID().toString().replace("-", ""),
                    domain,
                    resourcePublicId,
                    diffType,
                    truncate(redisSummary, 2000),
                    truncate(mysqlSummary, 2000),
                    requestId);
        } catch (Exception ex) {
            log.warn("Failed to record migration diff: {}", ex.getMessage());
        }
    }

    public boolean itemAlreadyMigrated(String sourceKey, String domain) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM koc_migration_item
                     WHERE source_key = ? AND domain = ? AND result = 'MIGRATED'
                    """, Integer.class, sourceKey, domain);
            return count != null && count > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Paged list of migration batches, newest first. Returns empty when MySQL is unavailable. */
    public BatchPage listBatches(int page, int size, String domain) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return new BatchPage(List.of(), 0);
        }
        try {
            StringBuilder where = new StringBuilder("WHERE 1=1");
            java.util.List<Object> args = new java.util.ArrayList<>();
            if (domain != null && !domain.isBlank()) {
                where.append(" AND domain = ?");
                args.add(domain);
            }
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM koc_migration_batch " + where, Long.class, args.toArray());
            int offset = Math.max(0, (page - 1) * size);
            args.add(size);
            args.add(offset);
            java.util.List<BatchSummary> rows = jdbcTemplate.query(
                    "SELECT batch_id, domain, mode, status, started_at, finished_at, scanned, migrated, "
                            + "skipped, failed, checkpoint FROM koc_migration_batch "
                            + where
                            + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> new BatchSummary(
                            rs.getString("batch_id"),
                            rs.getString("domain"),
                            rs.getString("mode"),
                            rs.getString("status"),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("finished_at")),
                            rs.getLong("scanned"),
                            rs.getLong("migrated"),
                            rs.getLong("skipped"),
                            rs.getLong("failed"),
                            rs.getString("checkpoint")),
                    args.toArray());
            return new BatchPage(rows, total == null ? 0 : total);
        } catch (Exception ex) {
            log.warn("Failed to list migration batches: {}", ex.getMessage());
            return new BatchPage(List.of(), 0);
        }
    }

    /** Paged list of recorded diffs, newest first. Drives the cutover sign-off report. */
    public DiffPage listDiffs(int page, int size, String domain) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return new DiffPage(List.of(), 0);
        }
        try {
            StringBuilder where = new StringBuilder("WHERE 1=1");
            java.util.List<Object> args = new java.util.ArrayList<>();
            if (domain != null && !domain.isBlank()) {
                where.append(" AND domain = ?");
                args.add(domain);
            }
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM koc_migration_diff " + where, Long.class, args.toArray());
            int offset = Math.max(0, (page - 1) * size);
            args.add(size);
            args.add(offset);
            java.util.List<DiffSummary> rows = jdbcTemplate.query(
                    "SELECT public_id, domain, resource_public_id, diff_type, redis_summary, mysql_summary, "
                            + "request_id, occurred_at FROM koc_migration_diff "
                            + where
                            + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> new DiffSummary(
                            rs.getString("public_id"),
                            rs.getString("domain"),
                            rs.getString("resource_public_id"),
                            rs.getString("diff_type"),
                            rs.getString("redis_summary"),
                            rs.getString("mysql_summary"),
                            rs.getString("request_id"),
                            toInstant(rs.getTimestamp("occurred_at"))),
                    args.toArray());
            return new DiffPage(rows, total == null ? 0 : total);
        } catch (Exception ex) {
            log.warn("Failed to list migration diffs: {}", ex.getMessage());
            return new DiffPage(List.of(), 0);
        }
    }

    private static java.time.Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record BatchSummary(
            String batchId,
            String domain,
            String mode,
            String status,
            java.time.Instant startedAt,
            java.time.Instant finishedAt,
            long scanned,
            long migrated,
            long skipped,
            long failed,
            String checkpoint) {}

    public record BatchPage(java.util.List<BatchSummary> rows, long total) {}

    public record DiffSummary(
            String publicId,
            String domain,
            String resourcePublicId,
            String diffType,
            String redisSummary,
            String mysqlSummary,
            String requestId,
            java.time.Instant occurredAt) {}

    public record DiffPage(java.util.List<DiffSummary> rows, long total) {}

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
