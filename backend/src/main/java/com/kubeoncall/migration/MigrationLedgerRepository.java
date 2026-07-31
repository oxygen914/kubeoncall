package com.kubeoncall.migration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

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
        finishBatch(batchId, scanned, migrated, skipped, failed, checkpoint, "COMPLETED");
    }

    /** Marks the batch terminal state without conflating an interrupted or failed scan with success. */
    public void finishBatch(
            String batchId, long scanned, long migrated, long skipped, long failed, String checkpoint, String status) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || batchId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    UPDATE koc_migration_batch
                       SET status = ?, finished_at = ?, scanned = ?, migrated = ?,
                           skipped = ?, failed = ?, checkpoint = ?
                     WHERE batch_id = ?
                    """,
                    normalizeBatchStatus(status),
                    Timestamp.from(Instant.now()),
                    scanned,
                    migrated,
                    skipped,
                    failed,
                    checkpoint,
                    batchId);
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

    /**
     * Claims one immutable legacy source key inside the caller's fenced MySQL transaction.
     *
     * <p>The unique primary key on {@code (domain, source_key)} makes a replay after a missing
     * Redis cursor a no-op rather than a duplicate fact write.</p>
     *
     * @return {@code 1} only for the runner that acquired the claim; {@code 0} for a prior run
     */
    public int claimSourceKey(String domain, String sourceKey, String targetPublicId) {
        JdbcTemplate jdbcTemplate = requireJdbc("claim migration source key");
        return jdbcTemplate.update(
                "INSERT IGNORE INTO koc_migration_source_claim (domain, source_key, target_public_id) VALUES (?, ?, ?)",
                truncate(domain, 64),
                truncate(sourceKey, 512),
                targetPublicId);
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

    /** Aggregates every completed SHADOW comparison into a minute bucket for cutover sign-off. */
    public void recordShadowComparison(String domain, boolean mismatch) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || domain == null || domain.isBlank()) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO koc_migration_shadow_observation
                      (domain, observed_minute, comparison_count, mismatch_count)
                    VALUES (?, DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%d %H:%i:00'), 1, ?)
                    ON DUPLICATE KEY UPDATE comparison_count = comparison_count + 1,
                                            mismatch_count = mismatch_count + VALUES(mismatch_count)
                    """, truncate(domain, 64), mismatch ? 1 : 0);
        } catch (Exception ex) {
            log.warn("Failed to record migration SHADOW comparison: {}", ex.getMessage());
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

    /**
     * Loads the opaque Redis SCAN cursor persisted after the last fully processed page.
     *
     * <p>Unlike the best-effort audit methods in this repository, checkpoint access is strict: an
     * unavailable ledger must stop an apply run rather than silently restart it from cursor zero.</p>
     */
    public ScanCheckpoint loadScanCheckpoint(String domain) {
        JdbcTemplate jdbcTemplate = requireJdbc("load scan checkpoint");
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT redis_cursor, completed FROM koc_migration_scan_checkpoint WHERE domain = ?",
                    (rs, rowNum) -> new ScanCheckpoint(rs.getString("redis_cursor"), rs.getBoolean("completed")),
                    domain);
        } catch (EmptyResultDataAccessException ex) {
            return ScanCheckpoint.initial();
        }
    }

    /** Advances a domain only after its entire current Redis SCAN page has been processed. */
    public void advanceScanCheckpoint(String domain, String redisCursor, boolean completed) {
        JdbcTemplate jdbcTemplate = requireJdbc("advance scan checkpoint");
        jdbcTemplate.update("""
                INSERT INTO koc_migration_scan_checkpoint (domain, redis_cursor, completed)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE redis_cursor = VALUES(redis_cursor), completed = VALUES(completed)
                """, domain, normalizeRedisCursor(redisCursor), completed);
    }

    /**
     * Advances the domain's durable MySQL generation after the caller owns its Redis lease.
     *
     * <p>The returned generation must be supplied to {@link #withWriteFence(MigrationWriteFence,
     * Runnable)} for every MySQL fact write. A later owner advances the generation before it starts
     * writing, which fences an old Redis owner even if that process resumes after its TTL expired.</p>
     */
    public MigrationWriteFence claimWriteFence(String domain, String ownerToken) {
        if (domain == null || domain.isBlank() || ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("Migration fence requires a domain and owner token");
        }
        JdbcTemplate jdbcTemplate = requireJdbc("claim write fence");
        return inTransaction(jdbcTemplate, "claim migration write fence", () -> {
            jdbcTemplate.update("""
                    INSERT INTO koc_migration_write_fence (domain, owner_token, generation)
                    VALUES (?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                      owner_token = VALUES(owner_token),
                      generation = generation + 1
                    """, domain, ownerToken);
            Long generation = jdbcTemplate.queryForObject(
                    "SELECT generation FROM koc_migration_write_fence "
                            + "WHERE domain = ? AND owner_token = ? FOR UPDATE",
                    Long.class,
                    domain,
                    ownerToken);
            if (generation == null) {
                throw new IllegalStateException("Migration write fence could not be claimed for " + domain);
            }
            return new MigrationWriteFence(domain, ownerToken, generation);
        });
    }

    /**
     * Executes a MySQL fact write only while the supplied owner/generation is still current.
     *
     * <p>The {@code FOR UPDATE} lock is deliberately held for the complete callback transaction:
     * a successor cannot advance the generation between verification and the fact write, and a
     * stale owner cannot pass verification once that successor commits its claim.</p>
     */
    public void withWriteFence(MigrationWriteFence fence, Runnable write) {
        if (fence == null) {
            throw new IllegalArgumentException("Migration write fence is required for apply writes");
        }
        if (write == null) {
            throw new IllegalArgumentException("Migration write callback is required");
        }
        JdbcTemplate jdbcTemplate = requireJdbc("verify write fence");
        inTransaction(jdbcTemplate, "verify migration write fence", () -> {
            List<Long> generations = jdbcTemplate.query(
                    "SELECT generation FROM koc_migration_write_fence "
                            + "WHERE domain = ? AND owner_token = ? AND generation = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getLong("generation"),
                    fence.domain(),
                    fence.ownerToken(),
                    fence.generation());
            if (generations.isEmpty()) {
                throw new LostMigrationWriteFenceException("Migration write fence lost for domain=" + fence.domain());
            }
            write.run();
            return null;
        });
    }

    /** Advances a scan checkpoint only if this runner is still the current MySQL writer. */
    public void advanceScanCheckpointFenced(
            MigrationWriteFence fence, String domain, String redisCursor, boolean completed) {
        withWriteFence(fence, () -> advanceScanCheckpoint(domain, redisCursor, completed));
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
    public DiffPage listDiffs(int page, int size, String domain, String resolutionStatus) {
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
            if (resolutionStatus != null && !resolutionStatus.isBlank()) {
                where.append(" AND resolution_status = ?");
                args.add(normalizeResolutionStatus(resolutionStatus));
            }
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM koc_migration_diff " + where, Long.class, args.toArray());
            int offset = Math.max(0, (page - 1) * size);
            args.add(size);
            args.add(offset);
            java.util.List<DiffSummary> rows = jdbcTemplate.query(
                    "SELECT public_id, domain, resource_public_id, diff_type, redis_summary, mysql_summary, "
                            + "request_id, resolution_status, resolution_note, resolved_by, resolved_at, occurred_at "
                            + "FROM koc_migration_diff "
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
                            rs.getString("resolution_status"),
                            rs.getString("resolution_note"),
                            rs.getString("resolved_by"),
                            toInstant(rs.getTimestamp("resolved_at")),
                            toInstant(rs.getTimestamp("occurred_at"))),
                    args.toArray());
            return new DiffPage(rows, total == null ? 0 : total);
        } catch (Exception ex) {
            log.warn("Failed to list migration diffs: {}", ex.getMessage());
            return new DiffPage(List.of(), 0);
        }
    }

    public DiffPage listDiffs(int page, int size, String domain) {
        return listDiffs(page, size, domain, null);
    }

    /** Paged item-level backfill evidence, newest first. */
    public ItemPage listItems(int page, int size, String domain, String result, String batchId) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return new ItemPage(List.of(), 0);
        }
        try {
            StringBuilder where = new StringBuilder("WHERE 1=1");
            java.util.List<Object> args = new java.util.ArrayList<>();
            appendItemFilter(where, args, "domain", domain);
            appendItemFilter(where, args, "result", result);
            appendItemFilter(where, args, "batch_id", batchId);
            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM koc_migration_item " + where, Long.class, args.toArray());
            int offset = Math.max(0, (page - 1) * size);
            args.add(size);
            args.add(offset);
            List<ItemSummary> rows = jdbcTemplate.query(
                    "SELECT batch_id, source_key, domain, target_public_id, result, reason, occurred_at "
                            + "FROM koc_migration_item " + where
                            + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> new ItemSummary(
                            rs.getString("batch_id"),
                            rs.getString("source_key"),
                            rs.getString("domain"),
                            rs.getString("target_public_id"),
                            rs.getString("result"),
                            rs.getString("reason"),
                            toInstant(rs.getTimestamp("occurred_at"))),
                    args.toArray());
            return new ItemPage(rows, total == null ? 0 : total);
        } catch (Exception ex) {
            log.warn("Failed to list migration items: {}", ex.getMessage());
            return new ItemPage(List.of(), 0);
        }
    }

    /** Returns the observed SHADOW mismatch rate and unresolved diff count for a bounded window. */
    public DiffStatistics diffStatistics(String domain, int windowMinutes, double thresholdPercent) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return DiffStatistics.unavailable(thresholdPercent);
        }
        try {
            String normalizedDomain = domain == null || domain.isBlank() ? "active-alarm" : domain;
            long[] counts = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(comparison_count), 0), COALESCE(SUM(mismatch_count), 0) "
                            + "FROM koc_migration_shadow_observation WHERE domain = ? "
                            + "AND observed_minute >= DATE_SUB(UTC_TIMESTAMP(), INTERVAL ? MINUTE)",
                    (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)},
                    normalizedDomain,
                    windowMinutes);
            Long openDiffCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM koc_migration_diff WHERE domain = ? AND resolution_status = 'OPEN'",
                    Long.class,
                    normalizedDomain);
            long comparisons = counts == null ? 0 : counts[0];
            long mismatches = counts == null ? 0 : counts[1];
            double rate = comparisons == 0 ? 0d : mismatches * 100d / comparisons;
            return new DiffStatistics(
                    normalizedDomain,
                    windowMinutes,
                    comparisons,
                    mismatches,
                    rate,
                    thresholdPercent,
                    rate <= thresholdPercent,
                    openDiffCount == null ? 0 : openDiffCount,
                    true);
        } catch (Exception ex) {
            log.warn("Failed to calculate migration diff statistics: {}", ex.getMessage());
            return DiffStatistics.unavailable(thresholdPercent);
        }
    }

    /** Resolves an OPEN diff exactly once, preserving its operator and explanation for audit. */
    public boolean resolveDiff(String publicId, String resolutionStatus, String note, String operator) {
        JdbcTemplate jdbcTemplate = requireJdbc("resolve migration diff");
        String normalizedStatus = normalizeResolutionStatus(resolutionStatus);
        if ("OPEN".equals(normalizedStatus)) {
            throw new IllegalArgumentException("A migration diff must be resolved to a terminal status");
        }
        return jdbcTemplate.update(
                        "UPDATE koc_migration_diff SET resolution_status = ?, resolution_note = ?, resolved_by = ?, "
                                + "resolved_at = UTC_TIMESTAMP(6) WHERE public_id = ? AND resolution_status = 'OPEN'",
                        normalizedStatus,
                        truncate(note, 1000),
                        truncate(operator, 64),
                        publicId)
                == 1;
    }

    private static void appendItemFilter(
            StringBuilder where, java.util.List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String normalizeResolutionStatus(String resolutionStatus) {
        String normalized =
                resolutionStatus == null ? "" : resolutionStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("OPEN", "EXPLAINED", "RESOLVED", "ACCEPTED_RISK").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported migration diff resolution status: " + resolutionStatus);
        }
        return normalized;
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
            String resolutionStatus,
            String resolutionNote,
            String resolvedBy,
            java.time.Instant resolvedAt,
            java.time.Instant occurredAt) {}

    public record DiffPage(java.util.List<DiffSummary> rows, long total) {}

    public record ItemSummary(
            String batchId,
            String sourceKey,
            String domain,
            String targetPublicId,
            String result,
            String reason,
            java.time.Instant occurredAt) {}

    public record ItemPage(java.util.List<ItemSummary> rows, long total) {}

    public record DiffStatistics(
            String domain,
            int windowMinutes,
            long comparisonCount,
            long mismatchCount,
            double mismatchRatePercent,
            double thresholdPercent,
            boolean withinThreshold,
            long openDiffCount,
            boolean available) {
        private static DiffStatistics unavailable(double thresholdPercent) {
            return new DiffStatistics(null, 0, 0, 0, 0d, thresholdPercent, false, 0, false);
        }
    }

    public record ScanCheckpoint(String redisCursor, boolean completed) {

        public ScanCheckpoint {
            redisCursor = normalizeRedisCursor(redisCursor);
        }

        public static ScanCheckpoint initial() {
            return new ScanCheckpoint("0", false);
        }
    }

    public record MigrationWriteFence(String domain, String ownerToken, long generation) {}

    public static final class LostMigrationWriteFenceException extends IllegalStateException {

        public LostMigrationWriteFenceException(String message) {
            super(message);
        }
    }

    private JdbcTemplate requireJdbc(String operation) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Migration ledger unavailable: cannot " + operation);
        }
        return jdbcTemplate;
    }

    private static <T> T inTransaction(
            JdbcTemplate jdbcTemplate, String operation, java.util.concurrent.Callable<T> work) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("Migration ledger has no DataSource: cannot " + operation);
        }
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return transaction.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException("Migration ledger failed to " + operation, ex);
            }
        });
    }

    private static String normalizeRedisCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? "0" : cursor.trim();
    }

    private static String normalizeBatchStatus(String status) {
        if ("FAILED".equals(status) || "INTERRUPTED".equals(status) || "COMPLETED".equals(status)) {
            return status;
        }
        return "FAILED";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
