package com.kubeoncall.migration;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.correlation.ChangeEvent;
import com.kubeoncall.alarm.correlation.MysqlChangeEventRepository;
import com.kubeoncall.alarm.correlation.RedisChangeEventRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Backfills the Redis {@code alarm-change-events:timeline} ZSET into the MySQL {@code koc_change_event}
 * fact table (WBS-11 GAP-11-01). Unlike the alarm/approval runners this does not SCAN a key pattern:
 * the source is a single ZSET whose members are serialized {@link ChangeEvent} values, so it walks
 * the set with ZSCAN and persists each member. Re-runs are idempotent: each change id is checked
 * against the migration ledger before the fenced MySQL write, dry-run skips the write entirely, and
 * the ZSCAN cursor is checkpointed so an interrupted apply resumes. Single-flight lease prevents
 * concurrent runs. Failures are counted and logged, never abort the batch.
 */
@Service
public class ChangeEventBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ChangeEventBackfillRunner.class);
    private static final String DOMAIN = "change-event";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<MysqlChangeEventRepository> mysqlRepositoryProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final KubeOnCallProperties properties;
    private final MigrationRunControl runControl;

    public ChangeEventBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<MysqlChangeEventRepository> mysqlRepositoryProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            KubeOnCallProperties properties) {
        this(
                redisProvider,
                mysqlRepositoryProvider,
                ledgerProvider,
                objectMapperProvider,
                properties,
                MigrationRunControl.disabled());
    }

    @Autowired
    public ChangeEventBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<MysqlChangeEventRepository> mysqlRepositoryProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            KubeOnCallProperties properties,
            MigrationRunControl runControl) {
        this.redisProvider = redisProvider;
        this.mysqlRepositoryProvider = mysqlRepositoryProvider;
        this.ledgerProvider = ledgerProvider;
        this.objectMapperProvider = objectMapperProvider;
        this.properties = properties;
        this.runControl = runControl;
    }

    public BackfillResult run(Boolean dryRunOverride, String requestId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        MysqlChangeEventRepository mysql = mysqlRepositoryProvider.getIfAvailable();
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (redis == null || mysql == null || !mysql.isAvailable() || objectMapper == null) {
            return new BackfillResult(
                    0, 0, 0, 0, null, "redis, MySQL change-event repository or ObjectMapper unavailable", false);
        }
        // P0-5 parity: an explicit Apply is a deliberate operator action and must NOT be silently
        // turned back into a dry-run by the global default. Only when the caller does not express a
        // preference (null) do we fall back to the configured default.
        boolean dryRun = dryRunOverride == null ? properties.getDataMigration().isBackfillDryRun() : dryRunOverride;
        if (!dryRun && ledger == null) {
            return new BackfillResult(0, 0, 0, 0, null, "MySQL migration ledger unavailable for apply", false);
        }
        MigrationLedgerRepository.ScanCheckpoint checkpoint =
                dryRun ? MigrationLedgerRepository.ScanCheckpoint.initial() : ledger.loadScanCheckpoint(DOMAIN);
        if (checkpoint.completed()) {
            return new BackfillResult(0, 0, 0, 0, "0", "backfill checkpoint already completed", dryRun);
        }
        String mode = dryRun ? "DRY_RUN" : "APPLY";
        // P0-6 parity: single-flight per domain via a Redis SET NX lease.
        String leaseKey = "kubeoncall:migration:lock:" + DOMAIN;
        String leaseOwner = leaseOwner(requestId);
        if (!MigrationLease.acquire(redis, leaseKey, leaseOwner)) {
            return new BackfillResult(0, 0, 0, 0, null, "another backfill is already running for " + DOMAIN, false);
        }
        MigrationLedgerRepository.MigrationWriteFence writeFence = null;
        if (!dryRun) {
            try {
                writeFence = ledger.claimWriteFence(DOMAIN, leaseOwner);
            } catch (Exception ex) {
                MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner);
                return new BackfillResult(0, 0, 0, 0, null, "MySQL migration write fence unavailable", false);
            }
        }
        String batchId = ledger == null ? null : ledger.beginBatch(DOMAIN, mode, requestId);
        log.info("ChangeEvent backfill starting: mode={}, batchId={}, dryRun={}", mode, batchId, dryRun);

        AtomicLong scanned = new AtomicLong();
        AtomicLong migrated = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        long limit = Math.max(1, properties.getDataMigration().getBackfillScanLimit());
        int batchSize = Math.max(1, properties.getDataMigration().getBackfillBatchSize());
        String interruptionNote = null;
        String cursor = checkpoint.redisCursor();

        try {
            while (scanned.get() < limit) {
                if (!MigrationLease.renewIfOwned(redis, leaseKey, leaseOwner)) {
                    interruptionNote = "lease ownership lost before next source item";
                    break;
                }
                RedisCursorScanner.Page page =
                        RedisCursorScanner.zscan(redis, RedisChangeEventRepository.TIMELINE_KEY, cursor, batchSize);
                for (String member : page.keys()) {
                    runControl.beforeSourceItem();
                    scanned.incrementAndGet();
                    ChangeEvent event;
                    try {
                        event = objectMapper.readValue(member, ChangeEvent.class);
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.warn("ChangeEvent backfill failed to deserialize member: {}", ex.getMessage());
                        if (ledger != null) {
                            ledger.recordItem(batchId, member, DOMAIN, null, "FAILED", "malformed member json");
                        }
                        continue;
                    }
                    String sourceKey = event.changeId();
                    if (sourceKey == null || sourceKey.isBlank()) {
                        skipped.incrementAndGet();
                        continue;
                    }
                    try {
                        if (!dryRun) {
                            if (ledger.itemAlreadyMigrated(sourceKey, DOMAIN)) {
                                skipped.incrementAndGet();
                                ledger.recordItem(batchId, sourceKey, DOMAIN, null, "SKIPPED", "already migrated");
                                continue;
                            }
                            MigrationLedgerRepository.MigrationWriteFence activeFence = writeFence;
                            ledger.withWriteFence(activeFence, () -> mysql.saveIfAbsent(event));
                        }
                        migrated.incrementAndGet();
                        if (ledger != null) {
                            ledger.recordItem(batchId, sourceKey, DOMAIN, null, dryRun ? "DRY_RUN" : "MIGRATED", null);
                        }
                    } catch (MigrationLedgerRepository.LostMigrationWriteFenceException ex) {
                        interruptionNote = "MySQL write fence ownership lost before source item commit";
                        break;
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.warn("ChangeEvent backfill failed for changeId={}: {}", sourceKey, ex.getMessage());
                        if (ledger != null) {
                            ledger.recordItem(
                                    batchId, sourceKey, DOMAIN, null, "FAILED", truncate(ex.getMessage(), 900));
                        }
                    }
                }
                if (interruptionNote != null) {
                    break;
                }
                cursor = page.nextCursor();
                if (!dryRun) {
                    ledger.advanceScanCheckpointFenced(writeFence, DOMAIN, cursor, page.completed());
                }
                if (page.completed()) {
                    break;
                }
            }
        } catch (MigrationLedgerRepository.LostMigrationWriteFenceException ex) {
            log.warn("ChangeEvent backfill lost MySQL write fence at scanned={}", scanned.get());
            interruptionNote = "MySQL write fence ownership lost before checkpoint commit";
        } catch (Exception ex) {
            log.warn("ChangeEvent backfill scan interrupted at scanned={}: {}", scanned.get(), ex.getMessage());
            interruptionNote = "scan interrupted: " + ex.getMessage();
        } finally {
            if (!MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner)) {
                log.warn("ChangeEvent backfill lease was not released because this runner no longer owns it");
            }
        }
        String lastKey = cursor;
        if (ledger != null) {
            ledger.finishBatch(
                    batchId,
                    scanned.get(),
                    migrated.get(),
                    skipped.get(),
                    failed.get(),
                    lastKey,
                    batchStatus(interruptionNote));
        }
        String note = dryRun ? "dry-run (no MySQL writes)" : "applied";
        if (scanned.get() >= limit) {
            note = note + "; scan limit reached, rerun resumes from persisted Redis cursor";
        }
        if (interruptionNote != null) {
            note = note + "; " + interruptionNote;
        }
        log.info(
                "ChangeEvent backfill done: scanned={}, migrated={}, skipped={}, failed={}, lastKey={}, dryRun={}",
                scanned.get(),
                migrated.get(),
                skipped.get(),
                failed.get(),
                lastKey,
                dryRun);
        return new BackfillResult(scanned.get(), migrated.get(), skipped.get(), failed.get(), lastKey, note, dryRun);
    }

    private static String batchStatus(String interruptionNote) {
        if (interruptionNote == null) {
            return "COMPLETED";
        }
        return interruptionNote.startsWith("scan interrupted") ? "FAILED" : "INTERRUPTED";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String leaseOwner(String requestId) {
        String requestPart = requestId == null || requestId.isBlank() ? "unknown" : requestId;
        return requestPart + ":" + UUID.randomUUID();
    }

    public record BackfillResult(
            long scanned, long migrated, long skipped, long failed, String checkpoint, String note, boolean dryRun) {}
}
