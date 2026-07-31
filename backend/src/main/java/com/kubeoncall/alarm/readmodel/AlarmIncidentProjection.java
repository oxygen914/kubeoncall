package com.kubeoncall.alarm.readmodel;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.state.ActiveAlarmState;

/**
 * Mirrors the Redis active-alarm state into the MySQL read model. This is the shadow-write side of
 * the WBS-5 read-model migration: Redis remains the authoritative write path, and this projection
 * best-effort persists an incident row (plus a raw event and a status-history entry) so the
 * {@code /api/v1/alarms} surface can serve real persisted data. The three writes run in one local
 * JDBC transaction. Any failure is logged and swallowed so a MySQL outage can never block alarm
 * processing.
 *
 * <p>Incident upsert is keyed by {@code (fingerprint, cycle_no)}. A firing after a fully resolved
 * incident starts the next cycle; a firing while recovery is still pending stays in the current
 * cycle. P0/P1 recovery events enter {@code RECOVERY_PENDING}, while lower severities resolve
 * immediately. Delivery identity is checked before any incident mutation and is also protected by
 * the V2 {@code (source, delivery_id)} unique key.
 */
@Service
public class AlarmIncidentProjection {

    private static final Logger log = LoggerFactory.getLogger(AlarmIncidentProjection.class);
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectProvider<AlarmReadRepository> readRepositoryProvider;
    private final ObjectMapper objectMapper;

    public AlarmIncidentProjection(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<AlarmReadRepository> readRepositoryProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.readRepositoryProvider = readRepositoryProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        AlarmReadRepository repository = readRepositoryProvider.getIfAvailable();
        return repository != null && repository.isAvailable();
    }

    /**
     * Backfill entry point (WBS-11): projects an {@link ActiveAlarmState} recovered from Redis into
     * the MySQL read model without a live delivery event. A canonical {@link NormalizedAlarmEvent}
     * is reconstructed from the state so the same tested upsert path handles the migration; metric
     * precision that the original event carried is not recoverable from Redis and is left null, which
     * is acceptable since backfill targets list/detail readability, not metric reconstruction.
     */
    public void projectBackfill(ActiveAlarmState state) {
        if (state == null) {
            return;
        }
        NormalizedAlarmEvent canonical = new NormalizedAlarmEvent(
                state.alarmId(),
                state.fingerprint(),
                state.alertName(),
                "backfill",
                state.severity() == null ? null : state.severity().name(),
                state.severity(),
                inferResourceType(state),
                state.resourceName(),
                state.cluster(),
                state.namespace(),
                state.service(),
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                state.status(),
                state.lastSeen() == null ? java.time.Instant.now() : state.lastSeen(),
                null,
                java.util.Map.of());
        project(canonical, AlarmEvaluationResult.unmatched(state.severity(), "backfill"), state);
    }

    /**
     * MySQL-primary entry point. It constructs state from the incoming event instead of reading a
     * Redis snapshot and propagates a database failure to its caller; Redis can therefore remain a
     * compatibility projection rather than deciding whether the fact write succeeded.
     */
    public ActiveAlarmState projectPrimary(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        ActiveAlarmState state = primaryState(event, evaluation);
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || !isAvailable()) {
            throw new IllegalStateException("MySQL alarm projection unavailable for MYSQL_PRIMARY");
        }
        DeliveryIdentity delivery = deliveryIdentity(event);
        try {
            projectAtomically(jdbcTemplate, event, evaluation, state, delivery, false);
        } catch (DuplicateKeyException ex) {
            // Same idempotent delivery outcome as the Redis-primary projection path. A concurrent
            // primary writer may insert the event after this transaction's first existence check.
            if (!deliveryExists(jdbcTemplate, delivery)) {
                throw ex;
            }
        }
        return state;
    }

    private static ActiveAlarmState primaryState(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation) {
        Instant eventTime = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        AlarmStatus status = event.status() == null ? AlarmStatus.FIRING : event.status();
        AlarmSeverity severity = evaluation != null && evaluation.finalSeverity() != null
                ? evaluation.finalSeverity()
                : event.severity();
        return new ActiveAlarmState(
                event.fingerprint(),
                event.alarmId(),
                event.alertName(),
                event.cluster(),
                event.namespace(),
                event.service(),
                event.resourceName(),
                severity,
                status,
                evaluation == null ? null : evaluation.policyId(),
                eventTime,
                eventTime,
                1L);
    }

    private static com.kubeoncall.alarm.domain.AlarmResourceType inferResourceType(ActiveAlarmState state) {
        // Best-effort heuristic from the alert name; backfill cannot recover the original resource
        // type from Redis, so unknown is a safe fallback that the read model accepts.
        String name = state.alertName() == null ? "" : state.alertName().toUpperCase();
        if (name.contains("NODE")) {
            return com.kubeoncall.alarm.domain.AlarmResourceType.NODE;
        }
        if (name.contains("POD")) {
            return com.kubeoncall.alarm.domain.AlarmResourceType.POD;
        }
        if (name.contains("DEPLOYMENT")) {
            return com.kubeoncall.alarm.domain.AlarmResourceType.DEPLOYMENT;
        }
        return null;
    }

    /** Persist (or update) the incident implied by the prepared alarm event. */
    public void project(NormalizedAlarmEvent event, AlarmEvaluationResult evaluation, ActiveAlarmState state) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || !isAvailable() || state == null) {
            return;
        }
        DeliveryIdentity delivery = deliveryIdentity(event);
        try {
            projectAtomically(jdbcTemplate, event, evaluation, state, delivery, true);
        } catch (DuplicateKeyException ex) {
            // A concurrent retry can pass the pre-check while the first transaction is still open.
            // Its incident mutation is rolled back with the duplicate event insert, so it is safe to
            // classify the now-visible delivery as an idempotent no-op.
            if (deliveryExists(jdbcTemplate, delivery)) {
                log.debug(
                        "Alarm read-model delivery already projected: source={}, deliveryId={}",
                        delivery.source(),
                        delivery.id());
                return;
            }
            logProjectionFailure(state, ex);
        } catch (Exception ex) {
            logProjectionFailure(state, ex);
        }
    }

    private void projectAtomically(
            JdbcTemplate jdbcTemplate,
            NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState state,
            DeliveryIdentity delivery,
            boolean redisCumulativeCount) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            projectOnce(jdbcTemplate, event, evaluation, state, delivery, redisCumulativeCount);
            return;
        }

        TransientDataAccessException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setName("alarm-incident-projection");
            try {
                transaction.executeWithoutResult(
                        ignored -> projectOnce(jdbcTemplate, event, evaluation, state, delivery, redisCumulativeCount));
                return;
            } catch (TransientDataAccessException ex) {
                lastTransientFailure = ex;
                log.debug(
                        "Retrying alarm projection after transient database conflict: fingerprint={}, attempt={}",
                        state.fingerprint(),
                        attempt);
            }
        }
        throw lastTransientFailure;
    }

    private void projectOnce(
            JdbcTemplate jdbcTemplate,
            NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState state,
            DeliveryIdentity delivery,
            boolean redisCumulativeCount) {
        // This must remain the first database decision in the transaction. In particular, no
        // incident version/count update may happen before duplicate delivery detection.
        if (deliveryExists(jdbcTemplate, delivery)) {
            return;
        }
        IncidentProjectionResult incident =
                upsertIncident(jdbcTemplate, event, evaluation, state, redisCumulativeCount);
        insertEvent(jdbcTemplate, incident.id(), event, delivery);
        if (shouldRecordStatusHistory(incident.previousStatus(), incident.targetStatus())) {
            insertStatusHistory(jdbcTemplate, incident, event);
        }
    }

    private IncidentProjectionResult upsertIncident(
            JdbcTemplate jdbcTemplate,
            NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState state,
            boolean redisCumulativeCount) {
        IncidentRef latest = findLatestIncidentForUpdate(jdbcTemplate, state.fingerprint());
        String eventStatus = projectedStatus(event, state);
        boolean startsNewCycle = latest == null || startsNewCycle(latest.status(), eventStatus);
        String targetStatus =
                latest == null || startsNewCycle ? eventStatus : transitionStatus(latest.status(), eventStatus);
        if (!startsNewCycle) {
            updateIncident(jdbcTemplate, latest, event, state, targetStatus, redisCumulativeCount);
            return new IncidentProjectionResult(latest.id(), latest.status(), targetStatus);
        }

        int cycleNo = latest == null ? 1 : latest.cycleNo() + 1;
        long previousOccurrences = occurrenceCountBeforeCycle(jdbcTemplate, state.fingerprint(), cycleNo);
        boolean inserted;
        try {
            insertIncident(jdbcTemplate, event, evaluation, state, cycleNo, targetStatus, previousOccurrences);
            inserted = true;
        } catch (DuplicateKeyException ex) {
            // Another first writer (or next-cycle writer) won the unique
            // (fingerprint, cycle_no) race. Lock and update that row instead of dropping this
            // distinct delivery.
            inserted = false;
        }

        IncidentRef target = findIncidentForUpdate(jdbcTemplate, state.fingerprint(), cycleNo);
        if (target == null) {
            throw new IllegalStateException("Incident insert completed without a visible row for fingerprint="
                    + state.fingerprint()
                    + ", cycle="
                    + cycleNo);
        }
        if (!inserted) {
            updateIncident(jdbcTemplate, target, event, state, targetStatus, redisCumulativeCount);
            return new IncidentProjectionResult(target.id(), target.status(), targetStatus);
        }
        return new IncidentProjectionResult(target.id(), null, targetStatus);
    }

    private void insertIncident(
            JdbcTemplate jdbcTemplate,
            NormalizedAlarmEvent event,
            AlarmEvaluationResult evaluation,
            ActiveAlarmState state,
            int cycleNo,
            String targetStatus,
            long previousOccurrences) {
        String publicId = "alm_" + UUID.randomUUID().toString().replace("-", "");
        boolean laterCycle = cycleNo > 1;
        Instant eventTime = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        Instant firstSeen = laterCycle ? eventTime : state.firstSeen() == null ? eventTime : state.firstSeen();
        Instant lastSeen = laterCycle ? eventTime : state.lastSeen() == null ? firstSeen : state.lastSeen();
        AlarmSeverity severity = state.severity() == null ? AlarmSeverity.P3 : state.severity();
        long occurrenceCount = Math.max(1L, state.count() - previousOccurrences);
        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_incident
                  (public_id, fingerprint, cycle_no, alert_name, severity, severity_rank, status,
                   resource_type, resource_name, cluster_name, namespace_name, service_name,
                   metric_name, current_value, threshold_value, unit, labels_json, annotations_json,
                   first_seen, last_seen, resolved_at, occurrence_count, acknowledged,
                   policy_public_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, 1)
                """,
                publicId,
                state.fingerprint(),
                cycleNo,
                state.alertName(),
                severity.name(),
                severity.rank(),
                targetStatus,
                resourceType(event),
                state.resourceName() == null ? "" : state.resourceName(),
                state.cluster(),
                state.namespace(),
                state.service(),
                event.metricName(),
                event.currentValue(),
                evaluation == null ? null : evaluation.threshold(),
                event.unit(),
                toJson(event.labels()),
                toJson(event.annotations()),
                Timestamp.from(firstSeen),
                Timestamp.from(lastSeen),
                "RESOLVED".equals(targetStatus) ? Timestamp.from(lastSeen) : null,
                occurrenceCount,
                evaluation == null ? null : evaluation.policyId());
    }

    private void updateIncident(
            JdbcTemplate jdbcTemplate,
            IncidentRef incident,
            NormalizedAlarmEvent event,
            ActiveAlarmState state,
            String targetStatus,
            boolean redisCumulativeCount) {
        Instant lastSeen = state.lastSeen() == null ? Instant.now() : state.lastSeen();
        long occurrenceCount = incident.occurrenceCount();
        if ("FIRING".equals(targetStatus)) {
            if (redisCumulativeCount) {
                long previousOccurrences =
                        occurrenceCountBeforeCycle(jdbcTemplate, state.fingerprint(), incident.cycleNo());
                // Redis carries the cumulative count. Concurrent projections can arrive out of order,
                // so incrementing the persisted value would double-count when the newer cumulative
                // snapshot wins the insert race and an older snapshot updates it afterwards.
                occurrenceCount = mergedOccurrenceCount(incident.occurrenceCount(), state.count(), previousOccurrences);
            } else {
                occurrenceCount = incident.occurrenceCount() + 1;
            }
        }
        jdbcTemplate.update(
                """
                UPDATE koc_alarm_incident
                   SET status = ?, last_seen = ?, occurrence_count = ?,
                       resolved_at = ?, version = version + 1
                 WHERE id = ?
                """,
                targetStatus,
                Timestamp.from(lastSeen),
                occurrenceCount,
                "RESOLVED".equals(targetStatus) ? Timestamp.from(lastSeen) : null,
                incident.id());
    }

    private void insertEvent(
            JdbcTemplate jdbcTemplate, Long incidentId, NormalizedAlarmEvent event, DeliveryIdentity delivery) {
        String publicId = "aev_" + UUID.randomUUID().toString().replace("-", "");
        Instant received = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        AlarmStatus eventStatus = event.status() == null ? AlarmStatus.FIRING : event.status();
        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_event
                  (public_id, incident_id, source, delivery_id, event_status, starts_at, ends_at,
                   received_at, payload_checksum, labels_json, annotations_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId,
                incidentId,
                delivery.source(),
                delivery.id(),
                eventStatus.name(),
                null,
                null,
                Timestamp.from(received),
                delivery.checksum(),
                toJson(event.labels()),
                toJson(event.annotations()));
    }

    private void insertStatusHistory(
            JdbcTemplate jdbcTemplate, IncidentProjectionResult incident, NormalizedAlarmEvent event) {
        String publicId = "ash_" + UUID.randomUUID().toString().replace("-", "");
        Instant occurred = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_status_history
                  (public_id, incident_id, from_status, to_status, reason_code, reason,
                   actor_type, actor_id, request_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 'SYSTEM', NULL, ?, ?)
                """,
                publicId,
                incident.id(),
                incident.previousStatus(),
                incident.targetStatus(),
                reasonCode(incident.targetStatus()),
                event.summary(),
                event.fingerprint(),
                Timestamp.from(occurred));
    }

    private IncidentRef findLatestIncidentForUpdate(JdbcTemplate jdbcTemplate, String fingerprint) {
        List<IncidentRef> incidents = jdbcTemplate.query(
                """
                SELECT id, cycle_no, status, occurrence_count
                  FROM koc_alarm_incident
                 WHERE fingerprint = ? AND deleted_at IS NULL
                 ORDER BY cycle_no DESC
                 LIMIT 1
                 FOR UPDATE
                """,
                (rs, rowNum) -> new IncidentRef(
                        rs.getLong("id"),
                        rs.getInt("cycle_no"),
                        rs.getString("status"),
                        rs.getLong("occurrence_count")),
                fingerprint);
        return incidents.isEmpty() ? null : incidents.get(0);
    }

    private IncidentRef findIncidentForUpdate(JdbcTemplate jdbcTemplate, String fingerprint, int cycleNo) {
        List<IncidentRef> incidents = jdbcTemplate.query(
                """
                SELECT id, cycle_no, status, occurrence_count
                  FROM koc_alarm_incident
                 WHERE fingerprint = ? AND cycle_no = ? AND deleted_at IS NULL
                 FOR UPDATE
                """,
                (rs, rowNum) -> new IncidentRef(
                        rs.getLong("id"),
                        rs.getInt("cycle_no"),
                        rs.getString("status"),
                        rs.getLong("occurrence_count")),
                fingerprint,
                cycleNo);
        return incidents.isEmpty() ? null : incidents.get(0);
    }

    private long occurrenceCountBeforeCycle(JdbcTemplate jdbcTemplate, String fingerprint, int cycleNo) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(occurrence_count), 0)
                  FROM koc_alarm_incident
                 WHERE fingerprint = ? AND cycle_no < ? AND deleted_at IS NULL
                """, Long.class, fingerprint, cycleNo);
        return count == null ? 0L : count;
    }

    private boolean deliveryExists(JdbcTemplate jdbcTemplate, DeliveryIdentity delivery) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_event WHERE source = ? AND delivery_id = ?",
                Long.class,
                delivery.source(),
                delivery.id());
        return count != null && count > 0;
    }

    static String projectedStatus(NormalizedAlarmEvent event, ActiveAlarmState state) {
        AlarmStatus eventStatus = event.status() == null ? AlarmStatus.FIRING : event.status();
        if (eventStatus == AlarmStatus.RESOLVED) {
            AlarmSeverity severity = state.severity() == null ? event.severity() : state.severity();
            return severity == AlarmSeverity.P0 || severity == AlarmSeverity.P1 ? "RECOVERY_PENDING" : "RESOLVED";
        }
        return eventStatus.name();
    }

    static boolean startsNewCycle(String latestStatus, String targetStatus) {
        return "RESOLVED".equals(latestStatus) && "FIRING".equals(targetStatus);
    }

    static long mergedOccurrenceCount(long persistedCycleCount, long cumulativeStateCount, long previousCycleCount) {
        long stateCycleCount = Math.max(1L, cumulativeStateCount - previousCycleCount);
        return Math.max(persistedCycleCount, stateCycleCount);
    }

    static String transitionStatus(String latestStatus, String eventStatus) {
        if (!"FIRING".equals(eventStatus)) {
            return eventStatus;
        }
        return switch (latestStatus) {
            case "ACKNOWLEDGED" -> "ACKNOWLEDGED";
            case "SUPPRESSED" -> "SUPPRESSED";
            case "RECOVERY_PENDING" -> "FIRING";
            default -> eventStatus;
        };
    }

    static boolean shouldRecordStatusHistory(String previousStatus, String targetStatus) {
        return previousStatus == null || !previousStatus.equals(targetStatus);
    }

    private static String reasonCode(String status) {
        return switch (status) {
            case "RESOLVED" -> "ALARM_RESOLVED";
            case "RECOVERY_PENDING" -> "ALARM_RECOVERY_PENDING";
            case "SUPPRESSED" -> "ALARM_SUPPRESSED";
            default -> "ALARM_FIRING";
        };
    }

    private static String resourceType(NormalizedAlarmEvent event) {
        return event.resourceType() == null ? "UNKNOWN" : event.resourceType().name();
    }

    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] payloadChecksum(NormalizedAlarmEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String basis = safe(event.fingerprint())
                    + "|"
                    + (event.status() == null
                            ? AlarmStatus.FIRING.name()
                            : event.status().name())
                    + "|"
                    + safe(event.occurredAt())
                    + "|"
                    + safe(event.metadata().get("endsAt"));
            return digest.digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate alarm projection delivery identity", ex);
        }
    }

    private static DeliveryIdentity deliveryIdentity(NormalizedAlarmEvent event) {
        byte[] checksum = payloadChecksum(event);
        String source = event.source() == null || event.source().isBlank() ? "unknown" : event.source();
        return new DeliveryIdentity(source, hex(checksum), checksum);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void logProjectionFailure(ActiveAlarmState state, Exception ex) {
        log.warn(
                "Alarm read-model projection failed; alarm processing continues: fingerprint={}, errorType={}",
                state.fingerprint(),
                ex.getClass().getSimpleName());
    }

    /** Convenience for tests and the overview aggregate: count incidents by status. */
    public Map<String, Long> countByStatus() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || !isAvailable()) {
            return Map.of();
        }
        return jdbcTemplate
                .query(
                        "SELECT status, COUNT(*) AS c FROM koc_alarm_incident WHERE deleted_at IS NULL GROUP BY status",
                        (rs, rowNum) -> Map.entry(rs.getString("status"), rs.getLong("c")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
    }

    /** Convenience for the overview aggregate: count active incidents by severity. */
    public Map<String, Long> countActiveBySeverity() {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null || !isAvailable()) {
            return Map.of();
        }
        return jdbcTemplate
                .query(
                        "SELECT severity, COUNT(*) AS c FROM koc_alarm_incident WHERE deleted_at IS NULL AND status = 'FIRING' GROUP BY severity",
                        (rs, rowNum) -> Map.entry(rs.getString("severity"), rs.getLong("c")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private record DeliveryIdentity(String source, String id, byte[] checksum) {}

    private record IncidentRef(long id, int cycleNo, String status, long occurrenceCount) {}

    private record IncidentProjectionResult(long id, String previousStatus, String targetStatus) {}
}
