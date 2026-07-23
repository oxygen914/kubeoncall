package com.kubeoncall.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Applies Redis acknowledgement, silence and confirmed-recovery state to immutable MySQL command
 * facts after {@link LegacyAlarmCommandPreflightService} has identified the required actor mapping.
 *
 * <p>Every apply write is guarded by both the domain write fence and a durable
 * {@code (domain, source_key)} claim in the same transaction. A failed prerequisite deliberately
 * stops before checkpoint advancement so an operator can add an explicit mapping and resume.</p>
 */
@Service
public class LegacyAlarmCommandBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyAlarmCommandBackfillRunner.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final LegacyActorResolver actorResolver;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;
    private final MigrationRunControl runControl;

    public LegacyAlarmCommandBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            LegacyActorResolver actorResolver,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties) {
        this(
                redisProvider,
                jdbcTemplateProvider,
                ledgerProvider,
                actorResolver,
                objectMapper,
                properties,
                MigrationRunControl.disabled());
    }

    @Autowired
    public LegacyAlarmCommandBackfillRunner(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            LegacyActorResolver actorResolver,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties,
            MigrationRunControl runControl) {
        this.redisProvider = redisProvider;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.ledgerProvider = ledgerProvider;
        this.actorResolver = actorResolver;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.runControl = runControl;
    }

    public BackfillResult run(Kind kind, Boolean dryRunOverride, String requestId) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (redis == null || jdbcTemplate == null || ledger == null || !ledger.isAvailable()) {
            return new BackfillResult(0, 0, 0, 0, null, "Redis, MySQL or migration ledger unavailable", false);
        }
        boolean dryRun = dryRunOverride == null ? properties.getDataMigration().isBackfillDryRun() : dryRunOverride;
        String domain = kind.domain();
        MigrationLedgerRepository.ScanCheckpoint checkpoint =
                dryRun ? MigrationLedgerRepository.ScanCheckpoint.initial() : ledger.loadScanCheckpoint(domain);
        if (checkpoint.completed()) {
            return new BackfillResult(0, 0, 0, 0, "0", "backfill checkpoint already completed", dryRun);
        }
        String leaseKey = "kubeoncall:migration:lock:" + domain;
        String leaseOwner = leaseOwner(requestId);
        if (!MigrationLease.acquire(redis, leaseKey, leaseOwner)) {
            return new BackfillResult(0, 0, 0, 0, null, "another backfill is already running for " + domain, dryRun);
        }
        MigrationLedgerRepository.MigrationWriteFence fence = null;
        if (!dryRun) {
            try {
                fence = ledger.claimWriteFence(domain, leaseOwner);
            } catch (Exception ex) {
                MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner);
                return new BackfillResult(0, 0, 0, 0, null, "MySQL migration write fence unavailable", false);
            }
        }
        String mode = dryRun ? "DRY_RUN" : "APPLY";
        String batchId = ledger.beginBatch(domain, mode, requestId);
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
                RedisCursorScanner.Page page =
                        RedisCursorScanner.scan(redis, cursor, kind.keyPrefix() + "*", batchSize);
                for (String key : page.keys()) {
                    if (scanned.get() >= limit) {
                        scanLimitReached = true;
                        break;
                    }
                    if (kind == Kind.RECOVERY && "alarm-recovery:due".equals(key)) {
                        continue;
                    }
                    runControl.beforeSourceItem();
                    scanned.incrementAndGet();
                    try {
                        ItemResult result = process(redis, jdbcTemplate, ledger, fence, kind, key, dryRun, requestId);
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
                                domain,
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
                        ledger.recordItem(batchId, key, domain, null, "FAILED", interruption);
                        break;
                    }
                }
                if (interruption != null) {
                    break;
                }
                if (scanLimitReached) {
                    break;
                }
                cursor = page.nextCursor();
                if (!dryRun) {
                    ledger.advanceScanCheckpointFenced(fence, domain, cursor, page.completed());
                }
                if (page.completed()) {
                    break;
                }
            }
        } catch (Exception ex) {
            interruption = "scan interrupted: " + ex.getClass().getSimpleName();
        } finally {
            if (!MigrationLease.releaseIfOwned(redis, leaseKey, leaseOwner)) {
                log.warn("Legacy {} backfill lease was not released because ownership changed", kind.domain());
            }
        }
        String status = batchStatus(interruption);
        ledger.finishBatch(batchId, scanned.get(), migrated.get(), skipped.get(), failed.get(), cursor, status);
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
            JdbcTemplate jdbcTemplate,
            MigrationLedgerRepository ledger,
            MigrationLedgerRepository.MigrationWriteFence fence,
            Kind kind,
            String key,
            boolean dryRun,
            String requestId)
            throws Exception {
        String fingerprint = key.substring(kind.keyPrefix().length());
        if (fingerprint.isBlank()) {
            return ItemResult.blocked("blank fingerprint");
        }
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return ItemResult.skipped(null, "value no longer exists");
        }
        Map<String, Object> value = objectMapper.readValue(raw, MAP_TYPE);
        if (kind == Kind.RECOVERY && !"CONFIRMED".equals(text(value.get("status")))) {
            return ItemResult.skipped(null, "recovery state has no manual confirmation fact");
        }
        if ((kind == Kind.ACKNOWLEDGEMENT || kind == Kind.SILENCE) && legacyExpiryAlreadyElapsed(value)) {
            return ItemResult.skipped(null, "legacy command already expired; no incident state revival");
        }
        String actor = text(value.get(kind.actorField()));
        LegacyActorResolver.ResolvedActor resolved =
                actorResolver.resolve(actor).orElse(null);
        if (resolved == null) {
            return ItemResult.blocked("legacy actor is not an exact or explicitly mapped local username: " + actor);
        }
        Incident incident = findIncident(jdbcTemplate, fingerprint);
        if (incident == null) {
            return ItemResult.blocked("missing MySQL incident; backfill active alarm first");
        }
        String targetPublicId = targetPublicId(kind, key);
        if (dryRun) {
            return ItemResult.migrated(targetPublicId, "validated actor=" + resolved.username());
        }
        AtomicBoolean wrote = new AtomicBoolean();
        MigrationLedgerRepository.MigrationWriteFence currentFence = fence;
        ledger.withWriteFence(currentFence, () -> {
            int claimed = ledger.claimSourceKey(kind.domain(), key, targetPublicId);
            if (claimed == 0) {
                return;
            }
            Incident locked = findIncidentForUpdate(jdbcTemplate, fingerprint);
            if (locked == null) {
                throw new MigrationPrerequisiteException("missing MySQL incident during fenced apply");
            }
            apply(jdbcTemplate, kind, locked, resolved, value, targetPublicId, normalizedRequestId(requestId));
            wrote.set(true);
        });
        return wrote.get()
                ? ItemResult.migrated(targetPublicId, "applied actor=" + resolved.username())
                : ItemResult.skipped(targetPublicId, "source key already claimed");
    }

    private static void apply(
            JdbcTemplate jdbcTemplate,
            Kind kind,
            Incident incident,
            LegacyActorResolver.ResolvedActor actor,
            Map<String, Object> value,
            String targetPublicId,
            String requestId) {
        Instant occurredAt = requiredInstant(value.get(kind.occurredAtField()), kind.occurredAtField());
        String reason = text(value.get(kind.reasonField()));
        switch (kind) {
            case ACKNOWLEDGEMENT ->
                applyAcknowledgement(
                        jdbcTemplate, incident, actor, value, targetPublicId, requestId, occurredAt, reason);
            case SILENCE ->
                applySilence(jdbcTemplate, incident, actor, value, targetPublicId, requestId, occurredAt, reason);
            case RECOVERY ->
                applyRecovery(jdbcTemplate, incident, actor, value, targetPublicId, requestId, occurredAt, reason);
        }
    }

    private static void applyAcknowledgement(
            JdbcTemplate jdbcTemplate,
            Incident incident,
            LegacyActorResolver.ResolvedActor actor,
            Map<String, Object> value,
            String targetPublicId,
            String requestId,
            Instant occurredAt,
            String reason) {
        Instant expiresAt = optionalInstant(value.get("expiresAt"));
        String toStatus = "FIRING".equals(incident.status()) ? "ACKNOWLEDGED" : incident.status();
        jdbcTemplate.update(
                "UPDATE koc_alarm_incident SET status = ?, acknowledged = TRUE, acknowledged_by = ?, acknowledged_at = ?, version = version + 1 WHERE id = ?",
                toStatus,
                actor.account().id(),
                Timestamp.from(occurredAt),
                incident.id());
        jdbcTemplate.update(
                "INSERT INTO koc_alarm_acknowledgement (public_id, incident_id, acknowledged_by, reason, acknowledged_at, expires_at, request_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                targetPublicId,
                incident.id(),
                actor.account().id(),
                requiredText(reason, "reason"),
                Timestamp.from(occurredAt),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                requestId);
        insertHistory(
                jdbcTemplate,
                incident,
                toStatus,
                "ALARM_ACKNOWLEDGED",
                reason,
                actor.account().id(),
                requestId,
                occurredAt);
    }

    private static void applySilence(
            JdbcTemplate jdbcTemplate,
            Incident incident,
            LegacyActorResolver.ResolvedActor actor,
            Map<String, Object> value,
            String targetPublicId,
            String requestId,
            Instant occurredAt,
            String reason) {
        Instant expiresAt = requiredInstant(value.get("expiresAt"), "expiresAt");
        if (!expiresAt.isAfter(occurredAt)) {
            throw new MigrationPrerequisiteException("silence expiresAt must be after approvedAt");
        }
        String toStatus = "FIRING".equals(incident.status()) || "ACKNOWLEDGED".equals(incident.status())
                ? "SUPPRESSED"
                : incident.status();
        jdbcTemplate.update(
                "UPDATE koc_alarm_incident SET status = ?, version = version + 1 WHERE id = ?",
                toStatus,
                incident.id());
        jdbcTemplate.update(
                "INSERT INTO koc_alarm_silence (public_id, incident_id, status, approved_by, reason, approved_at, expires_at, request_id) VALUES (?, ?, 'APPROVED', ?, ?, ?, ?, ?)",
                targetPublicId,
                incident.id(),
                actor.account().id(),
                requiredText(reason, "reason"),
                Timestamp.from(occurredAt),
                Timestamp.from(expiresAt),
                requestId);
        insertHistory(
                jdbcTemplate,
                incident,
                toStatus,
                "ALARM_SILENCE_APPROVED",
                reason,
                actor.account().id(),
                requestId,
                occurredAt);
    }

    private static void applyRecovery(
            JdbcTemplate jdbcTemplate,
            Incident incident,
            LegacyActorResolver.ResolvedActor actor,
            Map<String, Object> value,
            String targetPublicId,
            String requestId,
            Instant occurredAt,
            String reason) {
        if (!Boolean.parseBoolean(String.valueOf(value.get("healthCheckPassed")))) {
            throw new MigrationPrerequisiteException("confirmed recovery must have healthCheckPassed=true");
        }
        String toStatus = "RECOVERY_PENDING".equals(incident.status()) ? "RESOLVED" : incident.status();
        jdbcTemplate.update(
                "UPDATE koc_alarm_incident SET status = ?, resolved_at = COALESCE(resolved_at, ?), version = version + 1 WHERE id = ?",
                toStatus,
                Timestamp.from(occurredAt),
                incident.id());
        jdbcTemplate.update(
                "INSERT INTO koc_alarm_recovery_confirmation (public_id, incident_id, confirmed_by, health_check_passed, note, confirmed_at, request_id) VALUES (?, ?, ?, TRUE, ?, ?, ?)",
                targetPublicId,
                incident.id(),
                actor.account().id(),
                requiredText(reason, "note"),
                Timestamp.from(occurredAt),
                requestId);
        insertHistory(
                jdbcTemplate,
                incident,
                toStatus,
                "MANUAL_RECOVERY_CONFIRMED",
                reason,
                actor.account().id(),
                requestId,
                occurredAt);
    }

    private static void insertHistory(
            JdbcTemplate jdbcTemplate,
            Incident incident,
            String toStatus,
            String reasonCode,
            String reason,
            long actorId,
            String requestId,
            Instant occurredAt) {
        String historyId = "ah_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                "INSERT INTO koc_alarm_status_history (public_id, incident_id, from_status, to_status, reason_code, reason, actor_type, actor_id, request_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?)",
                historyId,
                incident.id(),
                incident.status(),
                toStatus,
                reasonCode,
                reason,
                actorId,
                requestId,
                Timestamp.from(occurredAt));
    }

    private static Incident findIncident(JdbcTemplate jdbcTemplate, String fingerprint) {
        return jdbcTemplate
                .query(
                        "SELECT id, public_id, status FROM koc_alarm_incident WHERE fingerprint = ? AND deleted_at IS NULL ORDER BY cycle_no DESC, id DESC LIMIT 1",
                        (rs, rowNum) ->
                                new Incident(rs.getLong("id"), rs.getString("public_id"), rs.getString("status")),
                        fingerprint)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static Incident findIncidentForUpdate(JdbcTemplate jdbcTemplate, String fingerprint) {
        return jdbcTemplate
                .query(
                        "SELECT id, public_id, status FROM koc_alarm_incident WHERE fingerprint = ? AND deleted_at IS NULL ORDER BY cycle_no DESC, id DESC LIMIT 1 FOR UPDATE",
                        (rs, rowNum) ->
                                new Incident(rs.getLong("id"), rs.getString("public_id"), rs.getString("status")),
                        fingerprint)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String targetPublicId(Kind kind, String sourceKey) {
        UUID value = UUID.nameUUIDFromBytes((kind.domain() + ":" + sourceKey).getBytes(StandardCharsets.UTF_8));
        return kind.publicIdPrefix() + value.toString().replace("-", "");
    }

    private static Instant requiredInstant(Object value, String field) {
        Instant parsed = optionalInstant(value);
        if (parsed == null) {
            throw new MigrationPrerequisiteException(field + " must be an ISO-8601 timestamp");
        }
        return parsed;
    }

    private static Instant optionalInstant(Object value) {
        String text = text(value);
        try {
            return text == null ? null : Instant.parse(text);
        } catch (Exception ex) {
            throw new MigrationPrerequisiteException("timestamp is invalid");
        }
    }

    private static boolean legacyExpiryAlreadyElapsed(Map<String, Object> value) {
        Instant expiresAt = optionalInstant(value.get("expiresAt"));
        return expiresAt != null && !expiresAt.isAfter(Instant.now());
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MigrationPrerequisiteException(field + " must not be blank");
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
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

    public enum Kind {
        ACKNOWLEDGEMENT("alarm-ack", "alarm-ack:", "acknowledgedBy", "acknowledgedAt", "reason", "aack_"),
        SILENCE("alarm-silence", "alarm-silence-approval:", "approvedBy", "approvedAt", "reason", "asil_"),
        RECOVERY("alarm-recovery", "alarm-recovery:", "confirmedBy", "confirmedAt", "note", "arc_");

        private final String domain;
        private final String keyPrefix;
        private final String actorField;
        private final String occurredAtField;
        private final String reasonField;
        private final String publicIdPrefix;

        Kind(
                String domain,
                String keyPrefix,
                String actorField,
                String occurredAtField,
                String reasonField,
                String publicIdPrefix) {
            this.domain = domain;
            this.keyPrefix = keyPrefix;
            this.actorField = actorField;
            this.occurredAtField = occurredAtField;
            this.reasonField = reasonField;
            this.publicIdPrefix = publicIdPrefix;
        }

        String domain() {
            return domain;
        }

        String keyPrefix() {
            return keyPrefix;
        }

        String actorField() {
            return actorField;
        }

        String occurredAtField() {
            return occurredAtField;
        }

        String reasonField() {
            return reasonField;
        }

        String publicIdPrefix() {
            return publicIdPrefix;
        }
    }

    private record Incident(long id, String publicId, String status) {}

    private enum Outcome {
        MIGRATED,
        SKIPPED,
        BLOCKED
    }

    private record ItemResult(Outcome outcome, String targetPublicId, String reason) {
        static ItemResult migrated(String targetPublicId, String reason) {
            return new ItemResult(Outcome.MIGRATED, targetPublicId, reason);
        }

        static ItemResult skipped(String targetPublicId, String reason) {
            return new ItemResult(Outcome.SKIPPED, targetPublicId, reason);
        }

        static ItemResult blocked(String reason) {
            return new ItemResult(Outcome.BLOCKED, null, reason);
        }
    }

    private static final class MigrationPrerequisiteException extends RuntimeException {
        MigrationPrerequisiteException(String message) {
            super(message);
        }
    }

    public record BackfillResult(
            long scanned, long migrated, long skipped, long failed, String checkpoint, String note, boolean dryRun) {}
}
