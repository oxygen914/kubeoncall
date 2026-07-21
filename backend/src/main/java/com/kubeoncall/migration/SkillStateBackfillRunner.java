package com.kubeoncall.migration;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.skill.SkillStateStore;
import com.kubeoncall.skill.mysql.SkillStateRepository;

/**
 * Backfills the Redis {@code skill:disabled} set into the MySQL skill-state fact table (WBS-11
 * Phase 2). Unlike the alarm/approval runners this does not SCAN — the disabled state is a single
 * Redis Set, so it reads the whole set in one call and upserts each member as a disabled skill row.
 * Re-runs are idempotent: an already-disabled row is a no-op upsert. Single-flight lease prevents
 * concurrent runs.
 */
@Service
public class SkillStateBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillStateBackfillRunner.class);
    private static final String DOMAIN = "skill-state";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<SkillStateStore> skillStateStoreProvider;
    private final ObjectProvider<SkillStateRepository> skillStateRepositoryProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final KubeOnCallProperties properties;

    public SkillStateBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<SkillStateStore> skillStateStoreProvider,
            ObjectProvider<SkillStateRepository> skillStateRepositoryProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties) {
        this.redisProvider = redisProvider;
        this.skillStateStoreProvider = skillStateStoreProvider;
        this.skillStateRepositoryProvider = skillStateRepositoryProvider;
        this.ledgerProvider = ledgerProvider;
        this.properties = properties;
    }

    public BackfillResult run(Boolean dryRunOverride, String requestId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        SkillStateStore store = skillStateStoreProvider.getIfAvailable();
        SkillStateRepository repository = skillStateRepositoryProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (redis == null || store == null || repository == null || !repository.isAvailable()) {
            return new BackfillResult(
                    0, 0, 0, 0, null, "redis, SkillStateStore or MySQL skill repository unavailable", false);
        }
        boolean dryRun = dryRunOverride == null ? properties.getDataMigration().isBackfillDryRun() : dryRunOverride;
        String mode = dryRun ? "DRY_RUN" : "APPLY";
        String leaseKey = "kubeoncall:migration:lock:" + DOMAIN;
        Boolean acquired = redis.opsForValue().setIfAbsent(leaseKey, requestId, Duration.ofMinutes(30));
        if (!Boolean.TRUE.equals(acquired)) {
            return new BackfillResult(0, 0, 0, 0, null, "another backfill is already running for " + DOMAIN, false);
        }
        String batchId = ledger == null ? null : ledger.beginBatch(DOMAIN, mode, requestId);
        log.info("SkillState backfill starting: mode={}, batchId={}, dryRun={}", mode, batchId, dryRun);

        AtomicLong scanned = new AtomicLong();
        AtomicLong migrated = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        String checkpoint = null;

        try {
            Set<String> disabledIds = store.disabledIds();
            if (disabledIds == null || disabledIds.isEmpty()) {
                log.info("SkillState backfill: no disabled skills in Redis");
                if (ledger != null) {
                    ledger.finishBatch(batchId, 0, 0, 0, 0, null);
                }
                return new BackfillResult(0, 0, 0, 0, null, "no disabled skills", dryRun);
            }
            for (String skillId : disabledIds) {
                scanned.incrementAndGet();
                checkpoint = skillId;
                String sourceKey = "skill:disabled:" + skillId;
                try {
                    if (!dryRun) {
                        if (ledger != null && ledger.itemAlreadyMigrated(sourceKey, DOMAIN)) {
                            skipped.incrementAndGet();
                            ledger.recordItem(batchId, sourceKey, DOMAIN, null, "SKIPPED", "already migrated");
                            continue;
                        }
                        SkillStateRepository.UpsertSkillState command = new SkillStateRepository.UpsertSkillState(
                                "skst_" + UUID.randomUUID().toString().replace("-", ""),
                                skillId,
                                null,
                                null,
                                null,
                                false,
                                "LOADED",
                                null,
                                java.util.Map.of(),
                                Instant.now(),
                                null);
                        repository.upsert(command);
                    }
                    migrated.incrementAndGet();
                    if (ledger != null) {
                        ledger.recordItem(batchId, sourceKey, DOMAIN, null, dryRun ? "DRY_RUN" : "MIGRATED", null);
                    }
                } catch (Exception ex) {
                    failed.incrementAndGet();
                    log.warn("SkillState backfill failed for skillId={}: {}", skillId, ex.getMessage());
                    if (ledger != null) {
                        ledger.recordItem(batchId, sourceKey, DOMAIN, null, "FAILED", truncate(ex.getMessage(), 900));
                    }
                }
            }
        } finally {
            redis.delete(leaseKey);
        }
        if (ledger != null) {
            ledger.finishBatch(batchId, scanned.get(), migrated.get(), skipped.get(), failed.get(), checkpoint);
        }
        String note = dryRun ? "dry-run (no MySQL writes)" : "applied";
        log.info(
                "SkillState backfill done: scanned={}, migrated={}, skipped={}, failed={}, dryRun={}",
                scanned.get(),
                migrated.get(),
                skipped.get(),
                failed.get(),
                dryRun);
        return new BackfillResult(scanned.get(), migrated.get(), skipped.get(), failed.get(), checkpoint, note, dryRun);
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
