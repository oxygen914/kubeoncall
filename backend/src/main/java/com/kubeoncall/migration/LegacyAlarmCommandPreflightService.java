package com.kubeoncall.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.readmodel.AlarmReadRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;

/**
 * Read-only WBS-11 preflight for legacy Redis alarm command state.
 *
 * <p>The legacy acknowledgement, silence and recovery records store their actor as arbitrary text,
 * while the MySQL fact tables require a {@code koc_user} foreign key. This service deliberately
 * never guesses an identity or writes a fact: it tells an operator which exact source entries are
 * ready and which need an explicit identity mapping or an incident backfill first.</p>
 */
@Service
public class LegacyAlarmCommandPreflightService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<AlarmReadRepository> alarmRepositoryProvider;
    private final LegacyActorResolver actorResolver;
    private final ObjectMapper objectMapper;
    private final KubeOnCallProperties properties;

    public LegacyAlarmCommandPreflightService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<AlarmReadRepository> alarmRepositoryProvider,
            LegacyActorResolver actorResolver,
            ObjectMapper objectMapper,
            KubeOnCallProperties properties) {
        this.redisProvider = redisProvider;
        this.alarmRepositoryProvider = alarmRepositoryProvider;
        this.actorResolver = actorResolver;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public PreflightReport inspect() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        AlarmReadRepository alarms = alarmRepositoryProvider.getIfAvailable();
        if (redis == null || alarms == null || !alarms.isAvailable()) {
            return new PreflightReport(
                    0, 0, Map.of(), List.of(), "Redis, MySQL alarm repository or identity repository unavailable");
        }

        long limit = Math.max(1, properties.getDataMigration().getBackfillScanLimit());
        int batchSize = Math.max(1, properties.getDataMigration().getBackfillBatchSize());
        List<PreflightItem> items = new ArrayList<>();
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        long scanned = 0;
        for (Domain domain : Domain.values()) {
            String cursor = "0";
            while (scanned < limit) {
                RedisCursorScanner.Page page =
                        RedisCursorScanner.scan(redis, cursor, domain.keyPrefix() + "*", batchSize);
                for (String key : page.keys()) {
                    if (scanned >= limit) {
                        break;
                    }
                    if (domain == Domain.RECOVERY && "alarm-recovery:due".equals(key)) {
                        continue;
                    }
                    scanned++;
                    PreflightItem item = inspectItem(redis, alarms, domain, key);
                    items.add(item);
                    byOutcome.merge(item.outcome(), 1L, Long::sum);
                }
                cursor = page.nextCursor();
                if (page.completed() || scanned >= limit) {
                    break;
                }
            }
            if (scanned >= limit) {
                break;
            }
        }
        String note = scanned >= limit
                ? "scan limit reached; increase backfill-scan-limit to inspect remaining entries"
                : "read-only preflight; READY only means the current actor exactly matches a local username";
        long ready = byOutcome.getOrDefault("READY", 0L);
        return new PreflightReport(scanned, ready, Map.copyOf(byOutcome), List.copyOf(items), note);
    }

    private PreflightItem inspectItem(
            StringRedisTemplate redis, AlarmReadRepository alarms, Domain domain, String key) {
        String fingerprint = key.substring(domain.keyPrefix().length());
        if (fingerprint.isBlank()) {
            return new PreflightItem(domain.id(), key, null, null, "INVALID", "blank fingerprint");
        }
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return new PreflightItem(domain.id(), key, fingerprint, null, "MISSING_SOURCE", "value no longer exists");
        }
        try {
            Map<String, Object> value = objectMapper.readValue(raw, MAP_TYPE);
            String actor = text(value.get(domain.actorField()));
            if (actor == null) {
                return new PreflightItem(
                        domain.id(), key, fingerprint, null, "MISSING_ACTOR", domain.actorField() + " is blank");
            }
            if (alarms.findByFingerprint(fingerprint).isEmpty()) {
                return new PreflightItem(
                        domain.id(), key, fingerprint, actor, "MISSING_INCIDENT", "backfill active alarm first");
            }
            String targetUsername = actorResolver.mappedUsername(actor);
            var resolved = actorResolver.resolve(actor);
            if (resolved.isEmpty()) {
                return new PreflightItem(
                        domain.id(),
                        key,
                        fingerprint,
                        actor,
                        "MISSING_USER",
                        "target username " + targetUsername
                                + " does not exist; add an explicit mapping or create the user");
            }
            return new PreflightItem(
                    domain.id(),
                    key,
                    fingerprint,
                    actor,
                    "READY",
                    resolved.get().explicitMapping() ? "explicit username mapping" : "exact username match");
        } catch (Exception ex) {
            return new PreflightItem(
                    domain.id(),
                    key,
                    fingerprint,
                    null,
                    "INVALID",
                    "unreadable legacy value: " + ex.getClass().getSimpleName());
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private enum Domain {
        ACKNOWLEDGEMENT("acknowledgement", "alarm-ack:", "acknowledgedBy"),
        SILENCE("silence", "alarm-silence-approval:", "approvedBy"),
        RECOVERY("recovery", "alarm-recovery:", "confirmedBy");

        private final String id;
        private final String keyPrefix;
        private final String actorField;

        Domain(String id, String keyPrefix, String actorField) {
            this.id = id;
            this.keyPrefix = keyPrefix;
            this.actorField = actorField;
        }

        String id() {
            return id;
        }

        String keyPrefix() {
            return keyPrefix;
        }

        String actorField() {
            return actorField;
        }
    }

    public record PreflightReport(
            long scanned, long ready, Map<String, Long> byOutcome, List<PreflightItem> items, String note) {}

    public record PreflightItem(
            String domain, String sourceKey, String fingerprint, String actor, String outcome, String reason) {}
}
