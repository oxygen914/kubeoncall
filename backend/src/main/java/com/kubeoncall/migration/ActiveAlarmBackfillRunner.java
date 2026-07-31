package com.kubeoncall.migration;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Backfills Redis {@code alarm-active:*} entries into the MySQL alarm read model (WBS-11 Phase 2).
 * The runner is idempotent, observable and safe to re-run: each source key is checked against the
 * migration ledger before projection, dry-run skips the write entirely, and the scan is bounded and
 * batched so it never stalls a live Redis. Failures are counted and logged, never abort the batch.
 */
@Service
public class ActiveAlarmBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ActiveAlarmBackfillRunner.class);
    private static final String DOMAIN = "active-alarm";
    private static final String KEY_PATTERN = "alarm-active:*";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<ActiveAlarmStore> activeAlarmStoreProvider;
    private final ObjectProvider<AlarmIncidentProjection> projectionProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final KubeOnCallProperties properties;
    private final MigrationRunControl runControl;

    public ActiveAlarmBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<ActiveAlarmStore> activeAlarmStoreProvider,
            ObjectProvider<AlarmIncidentProjection> projectionProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties) {
        this(
                redisProvider,
                activeAlarmStoreProvider,
                projectionProvider,
                ledgerProvider,
                properties,
                MigrationRunControl.disabled());
    }

    @Autowired
    public ActiveAlarmBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<ActiveAlarmStore> activeAlarmStoreProvider,
            ObjectProvider<AlarmIncidentProjection> projectionProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties,
            MigrationRunControl runControl) {
        this.redisProvider = redisProvider;
        this.activeAlarmStoreProvider = activeAlarmStoreProvider;
        this.projectionProvider = projectionProvider;
        this.ledgerProvider = ledgerProvider;
        this.properties = properties;
        this.runControl = runControl;
    }

    public BackfillResult run(Boolean dryRunOverride, String requestId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        ActiveAlarmStore store = activeAlarmStoreProvider.getIfAvailable();
        AlarmIncidentProjection projection = projectionProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (redis == null || store == null || projection == null || !projection.isAvailable()) {
            return new BackfillResult(
                    0, 0, 0, 0, null, "redis, ActiveAlarmStore or MySQL projection unavailable", false);
        }
        // P0-5 fix: an explicit Apply (dryRunOverride=false) is a deliberate operator action and must
        // NOT be silently turned back into a dry-run by the global default. Only when the caller does
        // not express a preference (null) do we fall back to the configured default.
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
        // P0-6 fix: single-flight per domain via a Redis SET NX lease so two concurrent invocations
        // cannot double-scan and double-write. The lease auto-expires if a runner crashes.
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
        log.info("ActiveAlarm backfill starting: mode={}, batchId={}, dryRun={}", mode, batchId, dryRun);

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
                RedisCursorScanner.Page page = RedisCursorScanner.scan(redis, cursor, KEY_PATTERN, batchSize);
                for (String key : page.keys()) {
                    runControl.beforeSourceItem();
                    scanned.incrementAndGet();
                    String fingerprint = fingerprintFromKey(key);
                    if (fingerprint == null) {
                        skipped.incrementAndGet();
                        continue;
                    }
                    try {
                        ActiveAlarmState state = store.find(fingerprint).orElse(null);
                        if (state == null) {
                            skipped.incrementAndGet();
                            if (ledger != null) {
                                ledger.recordItem(batchId, key, DOMAIN, null, "SKIPPED", "state not found in redis");
                            }
                            continue;
                        }
                        if (!dryRun) {
                            if (ledger.itemAlreadyMigrated(key, DOMAIN)) {
                                skipped.incrementAndGet();
                                ledger.recordItem(batchId, key, DOMAIN, null, "SKIPPED", "already migrated");
                                continue;
                            }
                            MigrationLedgerRepository.MigrationWriteFence activeFence = writeFence;
                            ledger.withWriteFence(activeFence, () -> projection.projectBackfill(state));
                        }
                        migrated.incrementAndGet();
                        if (ledger != null) {
                            ledger.recordItem(batchId, key, DOMAIN, null, dryRun ? "DRY_RUN" : "MIGRATED", null);
                        }
                    } catch (MigrationLedgerRepository.LostMigrationWriteFenceException ex) {
                        interruptionNote = "MySQL write fence ownership lost before source item commit";
                        break;
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.warn("Backfill failed for key={}: {}", key, ex.getMessage());
                        if (ledger != null) {
                            ledger.recordItem(batchId, key, DOMAIN, null, "FAILED", ex.getMessage());
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
            log.warn("ActiveAlarm backfill lost MySQL write fence at scanned={}", scanned.get());
            interruptionNote = "MySQL write fence ownership lost before checkpoint commit";
        } catch (Exception ex) {
            log.warn("Backfill scan interrupted at scanned={}: {}", scanned.get(), ex.getMessage());
            interruptionNote = "scan interrupted: " + ex.getMessage();
        } finally {
            if (!MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner)) {
                log.warn("ActiveAlarm backfill lease was not released because this runner no longer owns it");
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
                "ActiveAlarm backfill done: scanned={}, migrated={}, skipped={}, failed={}, lastKey={}, dryRun={}",
                scanned.get(),
                migrated.get(),
                skipped.get(),
                failed.get(),
                lastKey,
                dryRun);
        return new BackfillResult(scanned.get(), migrated.get(), skipped.get(), failed.get(), lastKey, note, dryRun);
    }

    /** alarm-active:{fingerprint} → fingerprint. Keys without a body are unclassifiable. */
    private static String fingerprintFromKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0 || colon == key.length() - 1) {
            return null;
        }
        return key.substring(colon + 1);
    }

    private static String batchStatus(String interruptionNote) {
        if (interruptionNote == null) {
            return "COMPLETED";
        }
        return interruptionNote.startsWith("scan interrupted") ? "FAILED" : "INTERRUPTED";
    }

    private static String leaseOwner(String requestId) {
        String requestPart = requestId == null || requestId.isBlank() ? "unknown" : requestId;
        return requestPart + ":" + UUID.randomUUID();
    }

    public record BackfillResult(
            long scanned, long migrated, long skipped, long failed, String checkpoint, String note, boolean dryRun) {}
}
