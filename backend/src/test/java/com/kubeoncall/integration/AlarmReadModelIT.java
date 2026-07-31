package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.readmodel.AlarmQueryService;
import com.kubeoncall.alarm.readmodel.AlarmReadRepository;
import com.kubeoncall.alarm.readmodel.AlarmTimelineItem;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.AlarmWorkflowAuditRecorder;
import com.kubeoncall.workflow.AlarmWorkflowFactRecorder;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;

/**
 * Verifies the WBS-5 alarm read model end-to-end against MySQL: the shadow-write projection persists
 * an incident + event + history row, and the query service returns list/detail/timeline from the
 * persisted data. Runs under the {@code integration-test} profile (Failsafe).
 */
@Testcontainers
class AlarmReadModelIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private static JdbcTemplate jdbcTemplate;
    private static AlarmReadRepository repository;
    private static AlarmIncidentProjection projection;
    private static AlarmQueryService queryService;

    @BeforeAll
    static void setUp() {
        DataSource dataSource = DataSourceBuilder.create()
                .url(MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false")
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        repository = new AlarmReadRepository(jdbcTemplate, new ObjectMapper(), true);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider =
                (ObjectProvider<JdbcTemplate>) new SingletonObjectProvider<>(jdbcTemplate);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmReadRepository> repoProvider =
                (ObjectProvider<AlarmReadRepository>) new SingletonObjectProvider<>(repository);
        projection = new AlarmIncidentProjection(jdbcProvider, repoProvider, new ObjectMapper());
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmReadRepository> queryRepoProvider =
                (ObjectProvider<AlarmReadRepository>) new SingletonObjectProvider<>(repository);
        ObjectProvider<com.kubeoncall.alarm.state.ActiveAlarmStore> storeProvider = new SingletonObjectProvider<>(null);
        ObjectProvider<com.kubeoncall.migration.MigrationLedgerRepository> ledgerProvider =
                new SingletonObjectProvider<>(null);
        queryService = new AlarmQueryService(
                queryRepoProvider,
                storeProvider,
                ledgerProvider,
                new com.kubeoncall.common.config.KubeOnCallProperties());
    }

    @Test
    void shadowWritePersistsIncidentAndQueryReturnsIt() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "am-upstream-1",
                "fp-node-cpu-1",
                "NodeCpuHigh",
                "alertmanager",
                "warning",
                AlarmSeverity.P2,
                AlarmResourceType.NODE,
                "worker-01",
                "prod",
                null,
                null,
                "cpu_usage",
                92.5,
                90.0,
                "%",
                "5m",
                Map.of("team", "platform"),
                Map.of("summary", "CPU high"),
                null,
                AlarmStatus.FIRING,
                Instant.parse("2026-07-20T01:00:00Z"),
                "CPU high on worker-01",
                Map.of());
        ActiveAlarmState state = new ActiveAlarmState(
                "fp-node-cpu-1",
                "am-upstream-1",
                "NodeCpuHigh",
                "prod",
                null,
                null,
                "worker-01",
                AlarmSeverity.P2,
                AlarmStatus.FIRING,
                "policy-cpu",
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:10:00Z"),
                6);
        AlarmEvaluationResult evaluation = AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "test");

        projection.project(event, evaluation, state);

        AlarmQueryService.AlarmListResult list = queryService.list(new AlarmQueryService.AlarmListRequest(
                1, 20, "-lastSeen", "FIRING", null, "prod", null, null, "NodeCpuHigh"));
        assertThat(list.total()).isEqualTo(1);
        assertThat(list.items()).hasSize(1);
        AlarmQueryService.AlarmListItem item = list.items().get(0);
        assertThat(item.alertName()).isEqualTo("NodeCpuHigh");
        assertThat(item.severity()).isEqualTo("P2");
        assertThat(item.status()).isEqualTo("FIRING");
        assertThat(item.resource().name()).isEqualTo("worker-01");
        assertThat(item.occurrenceCount()).isEqualTo(6);

        Optional<AlarmQueryService.AlarmDetail> detail = queryService.detail(item.id());
        assertThat(detail).isPresent();
        assertThat(detail.get().labels()).containsEntry("team", "platform");
        assertThat(detail.get().metricName()).isEqualTo("cpu_usage");
        assertThat(detail.get().currentValue()).isEqualTo(92.5);

        List<AlarmTimelineItem> timeline = queryService.timeline(item.id(), 50, null);
        assertThat(timeline).isNotEmpty();
        assertThat(timeline.get(0).type()).isEqualTo("ALARM_FIRING");
    }

    @Test
    void mysqlPrimaryProjectionOwnsOccurrenceCountWithoutRedisState() {
        NormalizedAlarmEvent first = new NormalizedAlarmEvent(
                "am-mysql-primary",
                "fp-mysql-primary",
                "NodeMemoryHigh",
                "alertmanager",
                "warning",
                AlarmSeverity.P2,
                AlarmResourceType.NODE,
                "worker-primary",
                "prod",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                Instant.parse("2026-07-22T01:00:00Z"),
                "memory high",
                Map.of());
        NormalizedAlarmEvent second = new NormalizedAlarmEvent(
                first.alarmId(),
                first.fingerprint(),
                first.alertName(),
                first.source(),
                first.rawSeverity(),
                first.severity(),
                first.resourceType(),
                first.resourceName(),
                first.cluster(),
                first.namespace(),
                first.service(),
                first.metricName(),
                first.currentValue(),
                first.threshold(),
                first.unit(),
                first.duration(),
                first.labels(),
                first.annotations(),
                first.runbookId(),
                first.status(),
                Instant.parse("2026-07-22T01:01:00Z"),
                first.summary(),
                first.metadata());

        projection.projectPrimary(first, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "primary"));
        projection.projectPrimary(second, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "primary"));
        projection.projectPrimary(second, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "primary"));

        Long occurrenceCount = jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM koc_alarm_incident WHERE fingerprint = ? AND cycle_no = 1",
                Long.class,
                "fp-mysql-primary");
        Long eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_event e JOIN koc_alarm_incident i ON i.id = e.incident_id "
                        + "WHERE i.fingerprint = ?",
                Long.class,
                "fp-mysql-primary");
        assertThat(occurrenceCount).isEqualTo(2L);
        assertThat(eventCount).isEqualTo(2L);
    }

    @Test
    void repeatedShadowWriteUpdatesOccurrenceCount() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "am-upstream-2",
                "fp-disk-1",
                "NodeDiskFull",
                "alertmanager",
                "warning",
                AlarmSeverity.P1,
                AlarmResourceType.NODE,
                "worker-02",
                "prod",
                null,
                null,
                "disk_usage",
                95.0,
                85.0,
                "%",
                "5m",
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                Instant.parse("2026-07-20T02:00:00Z"),
                "disk full",
                Map.of());
        ActiveAlarmState first = new ActiveAlarmState(
                "fp-disk-1",
                "am-upstream-2",
                "NodeDiskFull",
                "prod",
                null,
                null,
                "worker-02",
                AlarmSeverity.P1,
                AlarmStatus.FIRING,
                "policy-disk",
                Instant.parse("2026-07-20T02:00:00Z"),
                Instant.parse("2026-07-20T02:05:00Z"),
                1);
        ActiveAlarmState second = new ActiveAlarmState(
                "fp-disk-1",
                "am-upstream-2",
                "NodeDiskFull",
                "prod",
                null,
                null,
                "worker-02",
                AlarmSeverity.P1,
                AlarmStatus.FIRING,
                "policy-disk",
                Instant.parse("2026-07-20T02:00:00Z"),
                Instant.parse("2026-07-20T02:15:00Z"),
                3);
        NormalizedAlarmEvent nextDelivery = withOccurredAt(event, Instant.parse("2026-07-20T02:10:00Z"));
        projection.project(event, AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "x"), first);
        projection.project(nextDelivery, AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "x"), second);

        AlarmQueryService.AlarmListResult list = queryService.list(
                new AlarmQueryService.AlarmListRequest(1, 20, null, "FIRING", "P1", null, null, null, "NodeDiskFull"));
        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).occurrenceCount()).isEqualTo(3);
        assertThat(list.items().get(0).version()).isEqualTo(2);
    }

    @Test
    void duplicateDeliveryDoesNotMutateIncidentEventOrHistory() {
        String fingerprint = "fp-delivery-dedup-1";
        NormalizedAlarmEvent event =
                event("delivery-1", fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T03:00:00Z");
        ActiveAlarmState first = state(fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T03:00:00Z", 1);
        ActiveAlarmState duplicate =
                state(fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T03:10:00Z", 99);

        projection.project(event, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"), first);
        projection.project(event, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"), duplicate);

        Map<String, Object> incident = jdbcTemplate.queryForMap("""
                SELECT occurrence_count, version
                  FROM koc_alarm_incident
                 WHERE fingerprint = ? AND cycle_no = 1
                """, fingerprint);
        assertThat(((Number) incident.get("occurrence_count")).longValue()).isEqualTo(1);
        assertThat(((Number) incident.get("version")).longValue()).isEqualTo(1);
        assertThat(count("koc_alarm_event", fingerprint)).isEqualTo(1);
        assertThat(count("koc_alarm_status_history", fingerprint)).isEqualTo(1);
    }

    @Test
    void highSeverityRecoveryWaitsForConfirmationWhileLowSeverityStartsANewCycleAfterResolution() {
        String manualFingerprint = "fp-manual-recovery-1";
        NormalizedAlarmEvent p1Firing =
                event("p1-fire", manualFingerprint, AlarmSeverity.P1, AlarmStatus.FIRING, "2026-07-20T04:00:00Z");
        NormalizedAlarmEvent p1Resolved =
                event("p1-resolve", manualFingerprint, AlarmSeverity.P1, AlarmStatus.RESOLVED, "2026-07-20T04:05:00Z");
        projection.project(
                p1Firing,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "x"),
                state(manualFingerprint, AlarmSeverity.P1, AlarmStatus.FIRING, "2026-07-20T04:00:00Z", 1));
        projection.project(
                p1Resolved,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "x"),
                state(manualFingerprint, AlarmSeverity.P1, AlarmStatus.RESOLVED, "2026-07-20T04:05:00Z", 1));

        Map<String, Object> manual = jdbcTemplate.queryForMap(
                "SELECT status, resolved_at, cycle_no FROM koc_alarm_incident WHERE fingerprint = ?",
                manualFingerprint);
        assertThat(manual.get("status")).isEqualTo("RECOVERY_PENDING");
        assertThat(manual.get("resolved_at")).isNull();
        assertThat(((Number) manual.get("cycle_no")).intValue()).isEqualTo(1);

        String automaticFingerprint = "fp-auto-recovery-1";
        NormalizedAlarmEvent p2Firing =
                event("p2-fire-1", automaticFingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T05:00:00Z");
        NormalizedAlarmEvent p2Resolved = event(
                "p2-resolve-1", automaticFingerprint, AlarmSeverity.P2, AlarmStatus.RESOLVED, "2026-07-20T05:05:00Z");
        NormalizedAlarmEvent p2Refiring =
                event("p2-fire-2", automaticFingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T05:10:00Z");
        projection.project(
                p2Firing,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"),
                state(automaticFingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T05:00:00Z", 1));
        projection.project(
                p2Resolved,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"),
                state(automaticFingerprint, AlarmSeverity.P2, AlarmStatus.RESOLVED, "2026-07-20T05:05:00Z", 1));
        projection.project(
                p2Refiring,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"),
                state(automaticFingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T05:10:00Z", 2));

        List<Map<String, Object>> cycles = jdbcTemplate.queryForList("""
                SELECT cycle_no, status, occurrence_count
                  FROM koc_alarm_incident
                 WHERE fingerprint = ?
                 ORDER BY cycle_no
                """, automaticFingerprint);
        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(0).get("status")).isEqualTo("RESOLVED");
        assertThat(cycles.get(1).get("status")).isEqualTo("FIRING");
        assertThat(((Number) cycles.get(1).get("cycle_no")).intValue()).isEqualTo(2);
        assertThat(((Number) cycles.get(1).get("occurrence_count")).longValue()).isEqualTo(1);
    }

    @Test
    void projectionRollsBackIncidentAndEventWhenHistoryInsertFails() {
        String fingerprint = "fp-projection-rollback-1";
        NormalizedAlarmEvent event =
                event("rollback-1", fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T06:00:00Z");
        event = new NormalizedAlarmEvent(
                event.alarmId(),
                event.fingerprint(),
                event.alertName(),
                event.source(),
                event.rawSeverity(),
                event.severity(),
                event.resourceType(),
                event.resourceName(),
                event.cluster(),
                event.namespace(),
                event.service(),
                event.metricName(),
                event.currentValue(),
                event.threshold(),
                event.unit(),
                event.duration(),
                event.labels(),
                event.annotations(),
                event.runbookId(),
                event.status(),
                event.occurredAt(),
                "x".repeat(1001),
                event.metadata());

        projection.project(
                event,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"),
                state(fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T06:00:00Z", 1));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM koc_alarm_incident WHERE fingerprint = ?", Long.class, fingerprint))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM koc_alarm_event e JOIN koc_alarm_incident i ON i.id = e.incident_id WHERE i.fingerprint = ?",
                        Long.class,
                        fingerprint))
                .isZero();
    }

    @Test
    void concurrentFirstDeliveriesConvergeOnOneIncident() throws Exception {
        String fingerprint = "fp-concurrent-first-write-1";
        NormalizedAlarmEvent first =
                event("race-1", fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T07:00:00Z");
        NormalizedAlarmEvent second =
                event("race-2", fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T07:01:00Z");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = executor.submit(() -> {
                await(start);
                projection.project(
                        first,
                        AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"),
                        state(fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T07:00:00Z", 1));
            });
            Future<?> two = executor.submit(() -> {
                await(start);
                projection.project(
                        second,
                        AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "x"),
                        state(fingerprint, AlarmSeverity.P2, AlarmStatus.FIRING, "2026-07-20T07:01:00Z", 2));
            });
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM koc_alarm_incident WHERE fingerprint = ?", Long.class, fingerprint))
                .isEqualTo(1);
        assertThat(count("koc_alarm_event", fingerprint)).isEqualTo(2);
        Map<String, Object> incident = jdbcTemplate.queryForMap(
                "SELECT occurrence_count, version FROM koc_alarm_incident WHERE fingerprint = ?", fingerprint);
        assertThat(((Number) incident.get("occurrence_count")).longValue()).isEqualTo(2);
        assertThat(((Number) incident.get("version")).longValue()).isEqualTo(2);
    }

    @Test
    void alarmWorkflowFactsLinkLatestExecutionAndReachListAndDetailReadModels() {
        String fingerprint = "fp-workflow-facts-read-model-1";
        Instant startedAt = Instant.parse("2026-07-20T08:00:00Z");
        NormalizedAlarmEvent alarm = event(
                "workflow-fact-delivery-1", fingerprint, AlarmSeverity.P1, AlarmStatus.FIRING, startedAt.toString());
        projection.project(
                alarm,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "workflow fact test"),
                state(fingerprint, AlarmSeverity.P1, AlarmStatus.FIRING, startedAt.toString(), 1));

        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider =
                (ObjectProvider<JdbcTemplate>) new SingletonObjectProvider<>(jdbcTemplate);
        AlarmWorkflowFactRecorder factRecorder = new AlarmWorkflowFactRecorder(
                new WorkflowExecutionRepository(jdbcTemplate, true),
                jdbcTemplate,
                new OperationAuditWriter(jdbcProvider, new ObjectMapper()),
                new OutboxWriter(jdbcProvider, new ObjectMapper()));
        factRecorder.record(new AlarmWorkflowAuditRecorder.AuditRequest(
                "legacy-alarm-workflow-1",
                "SUCCEEDED",
                true,
                false,
                "diagnosis completed",
                null,
                List.of("prometheus"),
                startedAt,
                alarm,
                AlarmEvaluationResult.unmatched(AlarmSeverity.P1, "workflow fact test"),
                null,
                null,
                null,
                List.of(
                        new NodeResult("diagnosis", NodeStatus.SUCCESS, "root cause found", Map.of()),
                        new NodeResult("notification", NodeStatus.SUCCESS, "notified", Map.of())),
                Map.of()));

        Map<String, Object> execution = jdbcTemplate.queryForMap("""
                SELECT id, public_id, type, trigger_type, trigger_public_id, status, risk_level, version
                  FROM koc_workflow_execution
                 WHERE dedupe_key LIKE 'legacy-alarm-workflow-1:%'
                """);
        assertThat(execution)
                .containsEntry("type", "ALARM")
                .containsEntry("trigger_type", "ALARM")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("risk_level", "HIGH");
        String executionPublicId = (String) execution.get("public_id");
        String incidentPublicId = (String) execution.get("trigger_public_id");
        assertThat(executionPublicId).startsWith("exe_");
        assertThat(incidentPublicId).startsWith("alm_");
        assertThat(((Number) execution.get("version")).longValue()).isEqualTo(2L);

        List<Map<String, Object>> nodes = jdbcTemplate.queryForList("""
                SELECT node_name, attempt, status, output_summary, version
                  FROM koc_workflow_node_execution
                 WHERE execution_id = ?
                 ORDER BY id
                """, execution.get("id"));
        assertThat(nodes).hasSize(2);
        assertThat(nodes)
                .extracting(row -> row.get("node_name"), row -> row.get("status"), row -> row.get("output_summary"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("diagnosis", "SUCCEEDED", "root cause found"),
                        org.assertj.core.groups.Tuple.tuple("notification", "SUCCEEDED", "notified"));
        assertThat(nodes).allSatisfy(row -> assertThat(((Number) row.get("version")).longValue())
                .isEqualTo(2L));

        Map<String, Object> incident = jdbcTemplate.queryForMap("""
                SELECT latest_execution_id
                  FROM koc_alarm_incident
                 WHERE public_id = ?
                """, incidentPublicId);
        assertThat(((Number) incident.get("latest_execution_id")).longValue())
                .isEqualTo(((Number) execution.get("id")).longValue());
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM koc_operation_audit
                         WHERE action = 'workflow.alarm.complete'
                           AND resource_public_id = ?
                           AND result = 'SUCCESS'
                        """, Long.class, executionPublicId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                          FROM koc_outbox_event
                         WHERE aggregate_type = 'execution'
                           AND aggregate_public_id = ?
                           AND event_type = 'execution.updated'
                        """, Long.class, executionPublicId))
                .isEqualTo(1L);

        AlarmQueryService.AlarmDetail detail =
                queryService.detail(incidentPublicId).orElseThrow();
        assertThat(detail.latestExecution()).isNotNull();
        assertThat(detail.latestExecution().id()).isEqualTo(executionPublicId);
        assertThat(detail.latestExecution().status()).isEqualTo("SUCCEEDED");
        AlarmQueryService.AlarmListItem item = queryService
                .list(new AlarmQueryService.AlarmListRequest(1, 100, null, null, null, null, null, null, null))
                .items()
                .stream()
                .filter(candidate -> incidentPublicId.equals(candidate.id()))
                .findFirst()
                .orElseThrow();
        assertThat(item.latestExecution()).isNotNull();
        assertThat(item.latestExecution().id()).isEqualTo(executionPublicId);
        assertThat(item.latestExecution().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void activeAlarmListExplainUsesBoundedStatusTimeIndex() {
        Instant historical = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 600; index++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO koc_alarm_incident
                      (public_id, fingerprint, cycle_no, alert_name, severity, severity_rank, status,
                       resource_type, resource_name, first_seen, last_seen)
                    VALUES (?, ?, 1, 'HistoricalAlarm', 'P3', 4, 'RESOLVED', 'NODE', 'historical-node', ?, ?)
                    """,
                    "alm_explain_" + index,
                    "fp_explain_" + index,
                    java.sql.Timestamp.from(historical),
                    java.sql.Timestamp.from(historical));
        }
        jdbcTemplate.execute("ANALYZE TABLE koc_alarm_incident");

        Map<String, Object> plan = jdbcTemplate.queryForMap("""
                EXPLAIN SELECT id
                  FROM koc_alarm_incident
                 WHERE deleted_at IS NULL AND status = 'FIRING'
                 ORDER BY last_seen DESC, id DESC
                 LIMIT 50
                """);

        assertThat(plan.get("key")).isEqualTo("idx_alarm_list_status_deleted_seen");
        assertThat(((Number) plan.get("rows")).longValue()).isLessThanOrEqualTo(16L);
    }

    private static NormalizedAlarmEvent event(
            String alarmId, String fingerprint, AlarmSeverity severity, AlarmStatus status, String occurredAt) {
        return new NormalizedAlarmEvent(
                alarmId,
                fingerprint,
                "ProjectionTestAlarm",
                "alertmanager",
                severity.name(),
                severity,
                AlarmResourceType.NODE,
                "worker-test",
                "prod",
                null,
                null,
                "test_metric",
                90.0,
                80.0,
                "%",
                "5m",
                Map.of(),
                Map.of(),
                null,
                status,
                Instant.parse(occurredAt),
                "projection test",
                Map.of());
    }

    private static ActiveAlarmState state(
            String fingerprint, AlarmSeverity severity, AlarmStatus status, String lastSeen, long count) {
        Instant observedAt = Instant.parse(lastSeen);
        return new ActiveAlarmState(
                fingerprint,
                fingerprint,
                "ProjectionTestAlarm",
                "prod",
                null,
                null,
                "worker-test",
                severity,
                status,
                "policy-test",
                observedAt,
                observedAt,
                count);
    }

    private static NormalizedAlarmEvent withOccurredAt(NormalizedAlarmEvent event, Instant occurredAt) {
        return new NormalizedAlarmEvent(
                event.alarmId(),
                event.fingerprint(),
                event.alertName(),
                event.source(),
                event.rawSeverity(),
                event.severity(),
                event.resourceType(),
                event.resourceName(),
                event.cluster(),
                event.namespace(),
                event.service(),
                event.metricName(),
                event.currentValue(),
                event.threshold(),
                event.unit(),
                event.duration(),
                event.labels(),
                event.annotations(),
                event.runbookId(),
                event.status(),
                occurredAt,
                event.summary(),
                event.metadata());
    }

    private static long count(String table, String fingerprint) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " t JOIN koc_alarm_incident i ON i.id = t.incident_id WHERE i.fingerprint = ?",
                Long.class,
                fingerprint);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent projection start");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent projection start", ex);
        }
    }

    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T instance;

        SingletonObjectProvider(T instance) {
            this.instance = instance;
        }

        @Override
        public T getObject(Object... args) {
            return instance;
        }

        @Override
        public T getObject() {
            return instance;
        }

        @Override
        public T getIfAvailable() {
            return instance;
        }

        @Override
        public T getIfUnique() {
            return instance;
        }
    }
}
