package com.kubeoncall.alarm.correlation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.common.config.DataMigrationProperties.ChangeEventReadSource;
import com.kubeoncall.common.config.DataMigrationProperties.ChangeEventWriteMode;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.migration.MigrationLedgerRepository;

/**
 * Finds recent, scope-matched changes and returns a bounded explainable correlation set.
 *
 * <p>WBS-11 GAP-11-01 read-source / write-mode switch: the configured
 * {@code changeEventReadSource} routes {@link #findBetween} between Redis and MySQL, while
 * {@code changeEventWriteMode} routes {@link #recordIfAbsent}. Defaults keep the pre-cutover
 * Redis-only path so this change is behaviour-preserving until an operator opts in. {@code SHADOW}
 * reads serve MySQL but also read Redis and record any disagreement in the migration ledger.
 */
@Service
public class ChangeCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(ChangeCorrelationService.class);

    private final RedisChangeEventRepository redisRepository;
    private final ObjectProvider<MysqlChangeEventRepository> mysqlRepositoryProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final KubeOnCallProperties properties;

    public ChangeCorrelationService(
            RedisChangeEventRepository redisRepository,
            ObjectProvider<MysqlChangeEventRepository> mysqlRepositoryProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties) {
        this.redisRepository = redisRepository;
        this.mysqlRepositoryProvider = mysqlRepositoryProvider;
        this.ledgerProvider = ledgerProvider;
        this.properties = properties;
    }

    public void record(ChangeEvent event) {
        recordIfAbsent(event);
    }

    public boolean recordIfAbsent(ChangeEvent event) {
        if (event == null || event.changeId() == null || event.changeId().isBlank()) {
            throw new IllegalArgumentException("changeId is required");
        }
        ChangeEventWriteMode writeMode = properties.getDataMigration().getChangeEventWriteMode();
        MysqlChangeEventRepository mysql = mysqlRepositoryProvider.getIfAvailable();
        return switch (writeMode) {
            case REDIS_PRIMARY -> redisRepository.saveIfAbsent(event);
            case MYSQL_PRIMARY -> {
                if (mysql == null || !mysql.isAvailable()) {
                    // MySQL disabled: fall back to Redis so ingestion never drops the event.
                    yield redisRepository.saveIfAbsent(event);
                }
                yield mysql.saveIfAbsent(event);
            }
            case DUAL_WRITE -> {
                if (mysql == null || !mysql.isAvailable()) {
                    yield redisRepository.saveIfAbsent(event);
                }
                // MySQL is the authoritative fact; Redis is a best-effort compatibility projection
                // so the read path can still serve while the cutover window is open.
                boolean primary = mysql.saveIfAbsent(event);
                try {
                    redisRepository.saveIfAbsent(event);
                } catch (RuntimeException ex) {
                    log.warn(
                            "DUAL_WRITE Redis compatibility projection failed; MySQL fact preserved: changeId={}, errorType={}",
                            event.changeId(),
                            ex.getClass().getSimpleName());
                }
                yield primary;
            }
        };
    }

    public List<ChangeEvent> findBetween(Instant from, Instant to, String cluster, String namespace) {
        ChangeEventReadSource source = properties.getDataMigration().getChangeEventReadSource();
        return switch (source) {
            case REDIS -> redisRepository.findBetween(from, to, cluster, namespace);
            case MYSQL -> mysqlFindBetween(from, to, cluster, namespace);
            case SHADOW -> shadowFindBetween(from, to, cluster, namespace);
        };
    }

    private List<ChangeEvent> mysqlFindBetween(Instant from, Instant to, String cluster, String namespace) {
        MysqlChangeEventRepository mysql = mysqlRepositoryProvider.getIfAvailable();
        if (mysql == null || !mysql.isAvailable()) {
            log.debug("changeEventReadSource=MYSQL but MySQL unavailable; falling back to Redis");
            return redisRepository.findBetween(from, to, cluster, namespace);
        }
        return mysql.findBetween(from, to, cluster, namespace);
    }

    /**
     * Serves MySQL, then also reads Redis and records any symmetric difference of change ids. The
     * MySQL result is always returned so shadow reads never alter the response.
     */
    private List<ChangeEvent> shadowFindBetween(Instant from, Instant to, String cluster, String namespace) {
        MysqlChangeEventRepository mysql = mysqlRepositoryProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (mysql == null || !mysql.isAvailable() || ledger == null) {
            return redisRepository.findBetween(from, to, cluster, namespace);
        }
        List<ChangeEvent> mysqlResult = mysql.findBetween(from, to, cluster, namespace);
        List<ChangeEvent> redisResult;
        try {
            redisResult = redisRepository.findBetween(from, to, cluster, namespace);
        } catch (RuntimeException ex) {
            log.debug(
                    "SHADOW Redis read failed for change events: errorType={}",
                    ex.getClass().getSimpleName());
            return mysqlResult;
        }
        Set<String> mysqlIds = new HashSet<>();
        for (ChangeEvent event : mysqlResult) {
            mysqlIds.add(event.changeId());
        }
        Set<String> redisIds = new HashSet<>();
        for (ChangeEvent event : redisResult) {
            redisIds.add(event.changeId());
        }
        List<String> onlyInRedis = new ArrayList<>();
        for (ChangeEvent event : redisResult) {
            if (!mysqlIds.contains(event.changeId())) {
                onlyInRedis.add(event.changeId());
            }
        }
        List<String> onlyInMysql = new ArrayList<>();
        for (ChangeEvent event : mysqlResult) {
            if (!redisIds.contains(event.changeId())) {
                onlyInMysql.add(event.changeId());
            }
        }
        boolean mismatch = !onlyInRedis.isEmpty() || !onlyInMysql.isEmpty();
        ledger.recordShadowComparison("change-event", mismatch);
        if (mismatch) {
            ledger.recordDiff(
                    "change-event",
                    null,
                    "SET_MISMATCH",
                    summarize("redis", redisResult.size(), onlyInRedis),
                    summarize("mysql", mysqlResult.size(), onlyInMysql),
                    null);
        }
        return mysqlResult;
    }

    private static String summarize(String side, int count, List<String> exclusiveIds) {
        return side + ":count=" + count + ",exclusive=" + (exclusiveIds.isEmpty() ? "none" : exclusiveIds);
    }

    public List<ChangeCorrelation> findRelatedChanges(NormalizedAlarmEvent alarm) {
        Instant occurredAt = alarm.occurredAt() == null ? Instant.now() : alarm.occurredAt();
        return findBetween(
                        occurredAt.minus(Duration.ofHours(1)),
                        occurredAt.plus(Duration.ofMinutes(30)),
                        alarm.cluster(),
                        alarm.namespace())
                .stream()
                .map(change -> correlate(alarm, change, occurredAt))
                .filter(correlation -> correlation.correlationScore() >= 0.3)
                .sorted(Comparator.comparingDouble(ChangeCorrelation::correlationScore)
                        .reversed())
                .limit(5)
                .toList();
    }

    private ChangeCorrelation correlate(NormalizedAlarmEvent alarm, ChangeEvent change, Instant occurredAt) {
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        double score = timeScore(change.changedAt(), occurredAt);
        if (score >= 0.25) {
            reasons.add("change occurred close to the alarm");
        }
        if (same(alarm.resourceName(), change.resourceName())) {
            score += 0.4;
            reasons.add("resource matches");
        } else if (same(alarm.service(), change.resourceName())) {
            score += 0.3;
            reasons.add("service matches");
        } else if (same(alarm.namespace(), change.namespace())) {
            score += 0.15;
            reasons.add("namespace matches");
        }
        score += riskWeight(change.changeType());
        if ("deployment_image_change".equalsIgnoreCase(change.changeType())) {
            suggestions.add("Review the deployment image change and submit an approved rollback if it is causal");
        }
        if ("configmap_change".equalsIgnoreCase(change.changeType())
                || "secret_change".equalsIgnoreCase(change.changeType())) {
            suggestions.add("Compare the changed configuration with the last known-good revision");
        }
        return new ChangeCorrelation(change, Math.min(1, score), String.join("; ", reasons), suggestions);
    }

    private double timeScore(Instant changedAt, Instant occurredAt) {
        long seconds = Math.abs(Duration.between(changedAt, occurredAt).toSeconds());
        return Math.max(0, 0.3 * (1 - seconds / 3600.0));
    }

    private double riskWeight(String changeType) {
        if (changeType == null) {
            return 0;
        }
        return switch (changeType.toLowerCase(java.util.Locale.ROOT)) {
            case "deployment_image_change", "node_drain" -> 0.18;
            case "deployment_env_change", "secret_change" -> 0.14;
            case "configmap_change" -> 0.12;
            case "hpa_spec_change" -> 0.08;
            default -> 0.04;
        };
    }

    private boolean same(String left, String right) {
        return left != null && !left.isBlank() && left.equalsIgnoreCase(right);
    }
}
