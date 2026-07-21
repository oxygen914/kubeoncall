package com.kubeoncall.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.approval.RedisApprovalRepository;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalRequest;

/**
 * Backfills Redis {@code approval-request:*} entries into the MySQL approval fact table (WBS-11
 * Phase 2, second migration domain). Mirrors the {@link ActiveAlarmBackfillRunner} contract: dry-run
 * / apply, single-flight per domain via a Redis lease, idempotent (source_key dedup), batched SCAN
 * and ledger-recorded. A pending approval whose execution or actor no longer exists in MySQL is
 * skipped (not failed) because the foreign keys cannot be satisfied — this is the correct outcome,
 * not a backfill bug.
 */
@Service
public class ApprovalBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovalBackfillRunner.class);
    private static final String DOMAIN = "approval";
    private static final String KEY_PATTERN = "approval-request:*";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<RedisApprovalRepository> redisApprovalProvider;
    private final ObjectProvider<MySqlApprovalRepository> mysqlApprovalProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final KubeOnCallProperties properties;

    public ApprovalBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<RedisApprovalRepository> redisApprovalProvider,
            ObjectProvider<MySqlApprovalRepository> mysqlApprovalProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties) {
        this.redisProvider = redisProvider;
        this.redisApprovalProvider = redisApprovalProvider;
        this.mysqlApprovalProvider = mysqlApprovalProvider;
        this.ledgerProvider = ledgerProvider;
        this.properties = properties;
    }

    public BackfillResult run(Boolean dryRunOverride, String requestId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        RedisApprovalRepository redisApproval = redisApprovalProvider.getIfAvailable();
        MySqlApprovalRepository mysqlApproval = mysqlApprovalProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (redis == null || redisApproval == null || mysqlApproval == null || !mysqlApproval.isAvailable()) {
            return new BackfillResult(
                    0, 0, 0, 0, null, "redis, RedisApprovalRepository or MySQL approval store unavailable", false);
        }
        boolean dryRun = dryRunOverride == null ? properties.getDataMigration().isBackfillDryRun() : dryRunOverride;
        String mode = dryRun ? "DRY_RUN" : "APPLY";
        String leaseKey = "kubeoncall:migration:lock:" + DOMAIN;
        Boolean acquired = redis.opsForValue().setIfAbsent(leaseKey, requestId, Duration.ofMinutes(30));
        if (!Boolean.TRUE.equals(acquired)) {
            return new BackfillResult(0, 0, 0, 0, null, "another backfill is already running for " + DOMAIN, false);
        }
        String batchId = ledger == null ? null : ledger.beginBatch(DOMAIN, mode, requestId);
        log.info("Approval backfill starting: mode={}, batchId={}, dryRun={}", mode, batchId, dryRun);

        AtomicLong scanned = new AtomicLong();
        AtomicLong migrated = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        final String[] lastKeyHolder = {null};
        long limit = Math.max(1, properties.getDataMigration().getBackfillScanLimit());
        int batchSize = Math.max(1, properties.getDataMigration().getBackfillBatchSize());
        ScanOptions options =
                ScanOptions.scanOptions().match(KEY_PATTERN).count(batchSize).build();
        String interruptionNote = null;

        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext() && scanned.get() < limit) {
                String key = cursor.next();
                scanned.incrementAndGet();
                lastKeyHolder[0] = key;
                String executionId = executionIdFromKey(key);
                if (executionId == null) {
                    skipped.incrementAndGet();
                    continue;
                }
                try {
                    ApprovalRequest request =
                            redisApproval.findByExecutionId(executionId).orElse(null);
                    if (request == null) {
                        skipped.incrementAndGet();
                        if (ledger != null) {
                            ledger.recordItem(batchId, key, DOMAIN, null, "SKIPPED", "approval not found in redis");
                        }
                        continue;
                    }
                    if (!dryRun) {
                        if (ledger != null && ledger.itemAlreadyMigrated(key, DOMAIN)) {
                            skipped.incrementAndGet();
                            ledger.recordItem(batchId, key, DOMAIN, null, "SKIPPED", "already migrated");
                            continue;
                        }
                        String publicId = "apv_" + UUID.randomUUID().toString().replace("-", "");
                        // actionType/riskLevel are derived at creation time from the workflow context
                        // and are not stored in the Redis ApprovalRequest, so the backfill cannot
                        // recover them; UNKNOWN/MEDIUM are safe placeholders that preserve the pending
                        // approval fact without blocking the cutover.
                        MySqlApprovalRepository.CreateApproval command = new MySqlApprovalRepository.CreateApproval(
                                publicId,
                                request.executionId(),
                                "UNKNOWN",
                                request.taskId(),
                                "MEDIUM",
                                java.util.Map.of(),
                                parseRequestedBy(request.requestedBy()),
                                request.requestedAt() == null ? Instant.now() : request.requestedAt(),
                                request.decidedAt() == null ? Instant.now().plusSeconds(1800) : request.decidedAt());
                        mysqlApproval.create(command);
                    }
                    migrated.incrementAndGet();
                    if (ledger != null) {
                        ledger.recordItem(batchId, key, DOMAIN, null, dryRun ? "DRY_RUN" : "MIGRATED", null);
                    }
                } catch (Exception ex) {
                    // FK violations (execution/user missing) land here as a skip-worthy skip, but we
                    // cannot cheaply distinguish them from a real failure, so we count as failed and
                    // record the reason for the operator to triage in the diff report.
                    failed.incrementAndGet();
                    log.warn("Approval backfill failed for key={}: {}", key, ex.getMessage());
                    if (ledger != null) {
                        ledger.recordItem(batchId, key, DOMAIN, null, "FAILED", truncate(ex.getMessage(), 900));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Approval backfill scan interrupted at scanned={}: {}", scanned.get(), ex.getMessage());
            interruptionNote = "scan interrupted: " + ex.getMessage();
        } finally {
            redis.delete(leaseKey);
        }
        String lastKey = lastKeyHolder[0];
        if (ledger != null) {
            ledger.finishBatch(batchId, scanned.get(), migrated.get(), skipped.get(), failed.get(), lastKey);
        }
        String note = dryRun ? "dry-run (no MySQL writes)" : "applied";
        if (scanned.get() >= limit) {
            note = note + "; scan limit reached, rerun to continue";
        }
        if (interruptionNote != null) {
            note = note + "; " + interruptionNote;
        }
        log.info(
                "Approval backfill done: scanned={}, migrated={}, skipped={}, failed={}, dryRun={}",
                scanned.get(),
                migrated.get(),
                skipped.get(),
                failed.get(),
                dryRun);
        return new BackfillResult(scanned.get(), migrated.get(), skipped.get(), failed.get(), lastKey, note, dryRun);
    }

    private static long parseRequestedBy(String requestedBy) {
        if (requestedBy == null || requestedBy.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(requestedBy.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String executionIdFromKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0 || colon == key.length() - 1) {
            return null;
        }
        return key.substring(colon + 1);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record BackfillResult(
            long scanned, long migrated, long skipped, long failed, String checkpoint, String note, boolean dryRun) {}
}
