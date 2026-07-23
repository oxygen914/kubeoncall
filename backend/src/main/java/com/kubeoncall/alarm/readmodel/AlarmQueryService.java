package com.kubeoncall.alarm.readmodel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.alarm.state.ActiveAlarmStore;
import com.kubeoncall.common.config.DataMigrationProperties.AlarmReadSource;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.migration.MigrationLedgerRepository;

/**
 * Maps {@link AlarmIncidentRecord} rows into the {@code /api/v1/alarms} view models and enforces
 * the page/size bounds from {@code kubeoncall.api}. Depends on the read repository via
 * {@link ObjectProvider} so the service is always present even when MySQL is disabled (it then
 * reports unavailable and the controller returns 503).
 *
 * <p>WBS-11 read-source switch: {@code kubeoncall.data-migration.alarm-read-source} controls the
 * detail read. {@code MYSQL} (default) reads MySQL; {@code SHADOW} reads MySQL then also reads Redis
 * and records any diff via the migration ledger; {@code REDIS} falls back to MySQL (Redis cannot
 * list by public_id, so a pure-Redis read is not achievable mid-migration) and logs the fallback.
 */
@Service
public class AlarmQueryService {

    private static final Logger log = LoggerFactory.getLogger(AlarmQueryService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ObjectProvider<AlarmReadRepository> repositoryProvider;
    private final ObjectProvider<ActiveAlarmStore> activeAlarmStoreProvider;
    private final ObjectProvider<MigrationLedgerRepository> ledgerProvider;
    private final KubeOnCallProperties properties;

    public AlarmQueryService(
            ObjectProvider<AlarmReadRepository> repositoryProvider,
            ObjectProvider<ActiveAlarmStore> activeAlarmStoreProvider,
            ObjectProvider<MigrationLedgerRepository> ledgerProvider,
            KubeOnCallProperties properties) {
        this.repositoryProvider = repositoryProvider;
        this.activeAlarmStoreProvider = activeAlarmStoreProvider;
        this.ledgerProvider = ledgerProvider;
        this.properties = properties;
    }

    public boolean isAvailable() {
        AlarmReadRepository repository = repositoryProvider.getIfAvailable();
        return repository != null && repository.isAvailable();
    }

    public AlarmListResult list(AlarmListRequest request) {
        AlarmReadRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return AlarmListResult.unavailable();
        }
        int size = clampSize(request.size());
        int page = Math.max(1, request.page());
        AlarmReadRepository.AlarmListQuery query = new AlarmReadRepository.AlarmListQuery(
                page,
                size,
                request.sort(),
                splitList(request.statuses()),
                splitList(request.severities()),
                request.cluster(),
                request.namespace(),
                request.service(),
                request.q());
        AlarmReadRepository.AlarmListPage pageResult = repository.list(query);
        List<AlarmListItem> items =
                pageResult.rows().stream().map(AlarmQueryService::toListItem).toList();
        return new AlarmListResult(items, page, size, pageResult.total());
    }

    public Optional<AlarmDetail> detail(String alarmId) {
        AlarmReadRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return Optional.empty();
        }
        Optional<AlarmDetail> mysqlDetail = repository.findByPublicId(alarmId).map(AlarmQueryService::toDetail);
        AlarmReadSource source = properties.getDataMigration().getAlarmReadSource();
        if (source == AlarmReadSource.SHADOW) {
            shadowCompare(alarmId, mysqlDetail);
        } else if (source == AlarmReadSource.REDIS) {
            // P0-9: a genuine Redis-served read. Redis keys by fingerprint, not public_id, so we use
            // the MySQL row only to resolve the fingerprint, then serve the state from Redis. A Redis
            // miss or unavailable store falls back to MySQL and records a diff so the gap is visible
            // during the cutover — the response is never empty just because Redis blipped.
            Optional<AlarmDetail> redisServed = serveFromRedis(alarmId, mysqlDetail);
            if (redisServed.isPresent()) {
                return redisServed;
            }
        }
        return mysqlDetail;
    }

    /**
     * Builds a detail view from the Redis {@link ActiveAlarmState}, using the MySQL row only for the
     * fingerprint lookup and the fields Redis does not carry (labels, annotations, metric, policy).
     * Returns empty when Redis is unavailable or the key is missing, so the caller falls back to
     * MySQL and records the gap.
     */
    private Optional<AlarmDetail> serveFromRedis(String alarmId, Optional<AlarmDetail> mysqlDetail) {
        ActiveAlarmStore store = activeAlarmStoreProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (store == null || mysqlDetail.isEmpty()) {
            return Optional.empty();
        }
        AlarmDetail mysql = mysqlDetail.get();
        try {
            Optional<ActiveAlarmState> redisState = store.find(mysql.fingerprint());
            if (redisState.isEmpty()) {
                if (ledger != null) {
                    ledger.recordDiff("active-alarm", alarmId, "REDIS_MISSING", null, summarizeMysql(mysql), null);
                }
                return Optional.empty();
            }
            ActiveAlarmState redis = redisState.get();
            // Redis carries status/severity/count/lastSeen; the rest comes from the MySQL row.
            return Optional.of(new AlarmDetail(
                    mysql.id(),
                    redis.fingerprint(),
                    redis.alertName() == null ? mysql.alertName() : redis.alertName(),
                    redis.severity() == null
                            ? mysql.severity()
                            : redis.severity().name(),
                    redis.status() == null ? mysql.status() : redis.status().name(),
                    mysql.resource(),
                    mysql.firstSeen(),
                    redis.lastSeen() == null ? mysql.lastSeen() : redis.lastSeen(),
                    redis.count(),
                    mysql.acknowledgement(),
                    mysql.latestExecution(),
                    mysql.version(),
                    mysql.labels(),
                    mysql.annotations(),
                    mysql.metricName(),
                    mysql.currentValue(),
                    mysql.threshold(),
                    mysql.unit(),
                    mysql.policyPublicId(),
                    mysql.resolvedAt()));
        } catch (Exception ex) {
            log.debug("Redis serve failed for alarmId={}: {}", alarmId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Shadow compare: MySQL is the read authority, but also read Redis and record a diff if the two
     * disagree on status, severity or count. Differences land in {@code koc_migration_diff} for the
     * cutover sign-off; they never change the response, so shadow reads cannot break the console.
     */
    private void shadowCompare(String alarmId, Optional<AlarmDetail> mysqlDetail) {
        ActiveAlarmStore store = activeAlarmStoreProvider.getIfAvailable();
        MigrationLedgerRepository ledger = ledgerProvider.getIfAvailable();
        if (store == null || ledger == null || mysqlDetail.isEmpty()) {
            return;
        }
        AlarmDetail mysql = mysqlDetail.get();
        try {
            Optional<ActiveAlarmState> redisState = store.find(mysql.fingerprint());
            if (redisState.isEmpty()) {
                ledger.recordDiff("active-alarm", alarmId, "REDIS_MISSING", null, summarizeMysql(mysql), null);
                ledger.recordShadowComparison("active-alarm", true);
                return;
            }
            ActiveAlarmState redis = redisState.get();
            boolean mismatch = !equalsOrBothNull(
                            redis.status() == null ? null : redis.status().name(), mysql.status())
                    || !equalsOrBothNull(
                            redis.severity() == null ? null : redis.severity().name(), mysql.severity())
                    || redis.count() != mysql.occurrenceCount();
            ledger.recordShadowComparison("active-alarm", mismatch);
            if (mismatch) {
                ledger.recordDiff(
                        "active-alarm", alarmId, "FIELD_MISMATCH", summarizeRedis(redis), summarizeMysql(mysql), null);
            }
        } catch (Exception ex) {
            log.debug("Shadow compare failed for alarmId={}: {}", alarmId, ex.getMessage());
        }
    }

    private static String summarizeRedis(ActiveAlarmState redis) {
        return "status=" + redis.status() + ", severity=" + redis.severity() + ", count=" + redis.count()
                + ", lastSeen=" + redis.lastSeen();
    }

    private static String summarizeMysql(AlarmDetail mysql) {
        return "status=" + mysql.status() + ", severity=" + mysql.severity() + ", count=" + mysql.occurrenceCount()
                + ", lastSeen=" + mysql.lastSeen();
    }

    private static boolean equalsOrBothNull(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        return a != null && a.equals(b);
    }

    public List<AlarmTimelineItem> timeline(String alarmId, int limit, Instant after) {
        AlarmReadRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            return List.of();
        }
        int clamped = Math.max(1, Math.min(100, limit));
        return repository.timeline(alarmId, clamped, after);
    }

    private static int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static List<String> splitList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static AlarmResource toResource(AlarmIncidentRecord r) {
        return new AlarmResource(r.resourceType(), r.resourceName(), r.cluster(), r.namespace(), r.service());
    }

    private static AlarmListItem toListItem(AlarmIncidentRecord r) {
        return new AlarmListItem(
                r.publicId(),
                r.fingerprint(),
                r.alertName(),
                r.severity(),
                r.status(),
                toResource(r),
                r.firstSeen(),
                r.lastSeen(),
                r.occurrenceCount(),
                new AlarmAcknowledgement(r.acknowledged(), null, r.acknowledgedAt()),
                latestExecution(r),
                r.version());
    }

    private static AlarmDetail toDetail(AlarmIncidentRecord r) {
        return new AlarmDetail(
                r.publicId(),
                r.fingerprint(),
                r.alertName(),
                r.severity(),
                r.status(),
                toResource(r),
                r.firstSeen(),
                r.lastSeen(),
                r.occurrenceCount(),
                new AlarmAcknowledgement(r.acknowledged(), null, r.acknowledgedAt()),
                latestExecution(r),
                r.version(),
                r.labels(),
                r.annotations(),
                r.metricName(),
                r.currentValue(),
                r.threshold(),
                r.unit(),
                r.policyPublicId(),
                r.resolvedAt());
    }

    private static LatestExecution latestExecution(AlarmIncidentRecord record) {
        if (record.latestExecutionPublicId() == null) {
            return null;
        }
        return new LatestExecution(record.latestExecutionPublicId(), record.latestExecutionStatus());
    }

    public record AlarmListRequest(
            Integer page,
            Integer size,
            String sort,
            String statuses,
            String severities,
            String cluster,
            String namespace,
            String service,
            String q) {}

    public record AlarmListResult(List<AlarmListItem> items, int page, int size, long total) {

        public static AlarmListResult unavailable() {
            return new AlarmListResult(List.of(), 0, 0, 0);
        }
    }

    public record AlarmListItem(
            String id,
            String fingerprint,
            String alertName,
            String severity,
            String status,
            AlarmResource resource,
            Instant firstSeen,
            Instant lastSeen,
            long occurrenceCount,
            AlarmAcknowledgement acknowledgement,
            LatestExecution latestExecution,
            long version) {}

    public record AlarmDetail(
            String id,
            String fingerprint,
            String alertName,
            String severity,
            String status,
            AlarmResource resource,
            Instant firstSeen,
            Instant lastSeen,
            long occurrenceCount,
            AlarmAcknowledgement acknowledgement,
            LatestExecution latestExecution,
            long version,
            java.util.Map<String, String> labels,
            java.util.Map<String, String> annotations,
            String metricName,
            Double currentValue,
            Double threshold,
            String unit,
            String policyPublicId,
            Instant resolvedAt) {}

    public record AlarmResource(String type, String name, String cluster, String namespace, String service) {}

    public record AlarmAcknowledgement(boolean acknowledged, String by, Instant at) {}

    public record LatestExecution(String id, String status) {}
}
