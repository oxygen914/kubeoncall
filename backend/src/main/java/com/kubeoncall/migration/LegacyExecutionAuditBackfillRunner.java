package com.kubeoncall.migration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.audit.ExecutionAuditRecord;

/**
 * Moves bounded Redis {@code execution-audit:*} records into append-only MySQL operation-audit
 * facts. The Redis sorted-set index is deliberately excluded: it is an index, not an audit record.
 *
 * <p>The source claim and fact write share the MySQL write-fence transaction, so replay after an
 * unadvanced Redis cursor cannot create a second operation-audit fact.</p>
 */
@Service
public class LegacyExecutionAuditBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyExecutionAuditBackfillRunner.class);
    private static final String DOMAIN = "execution-audit";
    private static final String KEY_PATTERN = "execution-audit:*";
    private static final String INDEX_KEY = "execution-audit:index";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final OperationAuditWriter operationAuditWriter;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final MigrationRunControl runControl;

    public LegacyExecutionAuditBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            OperationAuditWriter operationAuditWriter,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties) {
        this(
                redisProvider,
                ledgerProvider,
                operationAuditWriter,
                objectMapper,
                properties,
                MigrationRunControl.disabled());
    }

    @Autowired
    public LegacyExecutionAuditBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            OperationAuditWriter operationAuditWriter,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties,
            MigrationRunControl runControl) {
        this.redisProvider = redisProvider;
        this.ledgerProvider = ledgerProvider;
        this.operationAuditWriter = operationAuditWriter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runControl = runControl;
    }

    public BackfillResult run(Boolean dryRunOverride, String requestId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (redis == null || ledger == null || !ledger.isAvailable() || !operationAuditWriter.isAvailable()) {
            return new BackfillResult(
                    0, 0, 0, 0, null, "Redis, MySQL migration ledger or operation audit unavailable", false);
        }
        boolean dryRun = dryRunOverride == null ? properties.getDataMigration().isBackfillDryRun() : dryRunOverride;
        MigrationLedgerRepository.ScanCheckpoint checkpoint =
                dryRun ? MigrationLedgerRepository.ScanCheckpoint.initial() : ledger.loadScanCheckpoint(DOMAIN);
        if (checkpoint.completed()) {
            return new BackfillResult(0, 0, 0, 0, "0", "backfill checkpoint already completed", dryRun);
        }
        String leaseKey = "kubeoncall:migration:lock:" + DOMAIN;
        String leaseOwner = leaseOwner(requestId);
        if (!MigrationLease.acquire(redis, leaseKey, leaseOwner)) {
            return new BackfillResult(0, 0, 0, 0, null, "another backfill is already running for " + DOMAIN, dryRun);
        }
        MigrationLedgerRepository.MigrationWriteFence fence = null;
        if (!dryRun) {
            try {
                fence = ledger.claimWriteFence(DOMAIN, leaseOwner);
            } catch (Exception ex) {
                MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner);
                return new BackfillResult(0, 0, 0, 0, null, "MySQL migration write fence unavailable", false);
            }
        }
        String batchId = ledger.beginBatch(DOMAIN, dryRun ? "DRY_RUN" : "APPLY", requestId);
        AtomicLong scanned = new AtomicLong();
        AtomicLong migrated = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        String cursor = checkpoint.redisCursor();
        String interruption = null;
        boolean scanLimitReached = false;
        long limit = Math.max(1, properties.getDataMigration().getBackfillScanLimit());
        int batchSize = Math.max(1, properties.getDataMigration().getBackfillBatchSize());

        try {
            while (scanned.get() < limit) {
                if (!MigrationLease.renewIfOwned(redis, leaseKey, leaseOwner)) {
                    interruption = "lease ownership lost before next source item";
                    break;
                }
                RedisCursorScanner.Page page = RedisCursorScanner.scan(redis, cursor, KEY_PATTERN, batchSize);
                for (String key : page.keys()) {
                    if (scanned.get() >= limit) {
                        scanLimitReached = true;
                        break;
                    }
                    if (INDEX_KEY.equals(key)) {
                        continue;
                    }
                    runControl.beforeSourceItem();
                    scanned.incrementAndGet();
                    try {
                        ItemResult result = process(redis, ledger, fence, key, dryRun, requestId);
                        switch (result.outcome()) {
                            case MIGRATED -> migrated.incrementAndGet();
                            case SKIPPED -> skipped.incrementAndGet();
                            case BLOCKED -> {
                                failed.incrementAndGet();
                                interruption = result.reason();
                            }
                        }
                        ledger.recordItem(
                                batchId,
                                key,
                                DOMAIN,
                                result.targetPublicId(),
                                dryRun ? "DRY_RUN" : result.outcome().name(),
                                result.reason());
                        if (interruption != null) {
                            break;
                        }
                    } catch (MigrationLedgerRepository.LostMigrationWriteFenceException ex) {
                        interruption = "MySQL write fence ownership lost before source item commit";
                        break;
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        interruption = "source item failed: " + ex.getClass().getSimpleName();
                        ledger.recordItem(batchId, key, DOMAIN, null, "FAILED", interruption);
                        break;
                    }
                }
                if (interruption != null || scanLimitReached) {
                    break;
                }
                cursor = page.nextCursor();
                if (!dryRun) {
                    ledger.advanceScanCheckpointFenced(fence, DOMAIN, cursor, page.completed());
                }
                if (page.completed()) {
                    break;
                }
            }
        } catch (Exception ex) {
            interruption = "scan interrupted: " + ex.getClass().getSimpleName();
        } finally {
            if (!MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner)) {
                log.warn("Legacy execution-audit backfill lease was not released because ownership changed");
            }
        }
        ledger.finishBatch(
                batchId, scanned.get(), migrated.get(), skipped.get(), failed.get(), cursor, batchStatus(interruption));
        String note = dryRun ? "dry-run (no MySQL writes)" : "applied";
        if (interruption != null) {
            note = note + "; " + interruption + "; cursor was not advanced after the incomplete page";
        } else if (scanLimitReached) {
            note = note + "; scan limit reached, rerun resumes from persisted Redis cursor";
        }
        return new BackfillResult(scanned.get(), migrated.get(), skipped.get(), failed.get(), cursor, note, dryRun);
    }

    private ItemResult process(
            StringRedisTemplate redis,
            MigrationLedgerRepository ledger,
            MigrationLedgerRepository.MigrationWriteFence fence,
            String key,
            boolean dryRun,
            String requestId)
            throws Exception {
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return ItemResult.skipped(null, "value no longer exists");
        }
        ExecutionAuditRecord record = objectMapper.readValue(raw, ExecutionAuditRecord.class);
        if (record.executionId() == null || record.executionId().isBlank()) {
            return ItemResult.blocked("executionId is blank");
        }
        String targetPublicId = targetPublicId(key);
        if (dryRun) {
            return ItemResult.migrated(targetPublicId, "validated executionId=" + record.executionId());
        }
        AtomicBoolean wrote = new AtomicBoolean();
        ledger.withWriteFence(fence, () -> {
            int claimed = ledger.claimSourceKey(DOMAIN, key, targetPublicId);
            if (claimed == 0) {
                return;
            }
            operationAuditWriter.write(toOperationAudit(record, targetPublicId, requestId));
            wrote.set(true);
        });
        return wrote.get()
                ? ItemResult.migrated(targetPublicId, "applied executionId=" + record.executionId())
                : ItemResult.skipped(targetPublicId, "source key already claimed");
    }

    private static OperationAuditWriter.AuditEntry toOperationAudit(
            ExecutionAuditRecord record, String publicId, String requestId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("legacyExecutionAudit", true);
        after.put(
                "requestType",
                record.requestType() == null ? null : record.requestType().name());
        after.put("status", record.status());
        after.put("approvalRequired", record.approvalRequired());
        after.put("autoHandled", record.autoHandled());
        after.put("durationMs", record.durationMs());
        after.put("tools", record.tools());
        after.put("retryCount", record.retryCount());
        after.put("replanRequired", record.replanRequired());
        after.put("degraded", record.degraded());
        after.put("approvalLatencyMs", record.approvalLatencyMs());
        after.put("toolSuccessCount", record.toolSuccessCount());
        after.put("toolFailureCount", record.toolFailureCount());
        after.put("metadata", record.metadata());
        String status = record.status() == null ? "UNKNOWN" : record.status().trim();
        boolean failure = "FAILED".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status);
        return new OperationAuditWriter.AuditEntry(
                publicId,
                "SYSTEM",
                null,
                "legacy-execution-audit",
                "EXECUTION_AUDIT_"
                        + (record.requestType() == null
                                ? "UNKNOWN"
                                : record.requestType().name()),
                "WORKFLOW_EXECUTION",
                truncate(record.executionId(), 40),
                failure ? "FAILURE" : "SUCCESS",
                firstText(record.failureReason(), record.summary()),
                Map.of(),
                after,
                normalizedRequestId(requestId),
                null,
                null,
                null,
                record.occurredAt() == null ? Instant.now() : record.occurredAt());
    }

    private static String targetPublicId(String sourceKey) {
        UUID value = UUID.nameUUIDFromBytes((DOMAIN + ":" + sourceKey).getBytes(StandardCharsets.UTF_8));
        return "oaud_" + value.toString().replace("-", "");
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String normalizedRequestId(String requestId) {
        String value = requestId == null || requestId.isBlank() ? "migration-backfill" : requestId.trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static String leaseOwner(String requestId) {
        return normalizedRequestId(requestId) + ":" + UUID.randomUUID();
    }

    private static String batchStatus(String interruption) {
        if (interruption == null) {
            return "COMPLETED";
        }
        if (interruption.startsWith("lease ownership lost")
                || interruption.startsWith("MySQL write fence ownership lost")) {
            return "INTERRUPTED";
        }
        return "FAILED";
    }

    public record BackfillResult(
            long scanned, long migrated, long skipped, long failed, String checkpoint, String note, boolean dryRun) {}

    private enum Outcome {
        MIGRATED,
        SKIPPED,
        BLOCKED
    }

    private record ItemResult(Outcome outcome, String targetPublicId, String reason) {

        private static ItemResult migrated(String targetPublicId, String reason) {
            return new ItemResult(Outcome.MIGRATED, targetPublicId, reason);
        }

        private static ItemResult skipped(String targetPublicId, String reason) {
            return new ItemResult(Outcome.SKIPPED, targetPublicId, reason);
        }

        private static ItemResult blocked(String reason) {
            return new ItemResult(Outcome.BLOCKED, null, reason);
        }
    }
}
