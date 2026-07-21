package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

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
import com.kubeoncall.alarm.readmodel.AlarmCommandException;
import com.kubeoncall.alarm.readmodel.AlarmCommandService;
import com.kubeoncall.alarm.readmodel.AlarmIncidentProjection;
import com.kubeoncall.alarm.readmodel.AlarmReadRepository;
import com.kubeoncall.alarm.state.ActiveAlarmState;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.idempotency.IdempotencyService;

/**
 * Verifies the WBS-6 acknowledge command path end-to-end against MySQL: the optimistic-lock update,
 * ack + status-history + operation-audit + outbox rows all commit in one transaction, version
 * conflicts surface as {@code RESOURCE_VERSION_CONFLICT}, and a repeat of the same command is
 * idempotent. Runs under the {@code integration-test} profile (Failsafe).
 */
@Testcontainers
class AlarmCommandIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private static JdbcTemplate jdbcTemplate;
    private static AlarmReadRepository repository;
    private static AlarmCommandService commandService;
    private static IdempotencyService idempotencyService;
    private static AlarmIncidentProjection projection;
    private static org.springframework.jdbc.datasource.DataSourceTransactionManager txManager;

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
        jdbcTemplate.update("""
                INSERT INTO koc_user
                  (id, public_id, username, username_normalized, display_name, password_hash, status)
                VALUES (1, 'usr_alarm_command_it', 'alarm-command-it', 'alarm-command-it',
                        'Alarm Command IT', 'not-used-by-this-test', 'ACTIVE')
                """);
        repository = new AlarmReadRepository(jdbcTemplate, new ObjectMapper(), true);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider =
                (ObjectProvider<JdbcTemplate>) new SingletonObjectProvider<>(jdbcTemplate);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmReadRepository> repoProvider =
                (ObjectProvider<AlarmReadRepository>) new SingletonObjectProvider<>(repository);
        OperationAuditWriter auditWriter = new OperationAuditWriter(jdbcProvider, new ObjectMapper());
        OutboxWriter outboxWriter = new OutboxWriter(jdbcProvider, new ObjectMapper());
        projection = new AlarmIncidentProjection(jdbcProvider, repoProvider, new ObjectMapper());
        idempotencyService = new IdempotencyService(jdbcProvider, new ObjectMapper(), java.time.Duration.ofHours(1));
        // A real PlatformTransactionManager is needed for @Transactional; use DataSourceTransactionManager.
        txManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        // Construct the command service and wrap it in a transactional proxy so @Transactional works
        // outside a Spring Boot context (ack + audit + outbox must commit atomically).
        commandService =
                transactionalAlarmCommandService(repoProvider, jdbcProvider, auditWriter, outboxWriter, txManager);
    }

    @Test
    void acknowledgeCommitsAtomicallyAndIsIdempotent() {
        String alarmId = seedFiringAlarm("fp-ack-1", "NodeCpuHigh");
        long version = repository.findByPublicId(alarmId).orElseThrow().version();

        AlarmCommandService.AcknowledgeCommand command = new AlarmCommandService.AcknowledgeCommand(
                alarmId,
                version,
                1L,
                "USER",
                "Alice",
                "expanding capacity",
                Instant.parse("2026-07-20T01:20:00Z"),
                null,
                "req_ack_1",
                "127.0.0.1",
                "test");
        AlarmCommandService.AcknowledgeResult result = commandService.acknowledge(command);
        assertThat(result.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(result.version()).isEqualTo(version + 1);
        assertThat(repository.findByPublicId(alarmId).orElseThrow().status()).isEqualTo("ACKNOWLEDGED");

        // Ack row, status history, operation audit and outbox all committed together.
        Long ackRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_acknowledgement WHERE incident_id = (SELECT id FROM koc_alarm_incident WHERE public_id = ?)",
                Long.class,
                alarmId);
        assertThat(ackRows).isEqualTo(1L);
        Long historyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_status_history WHERE incident_id = (SELECT id FROM koc_alarm_incident WHERE public_id = ?) AND to_status = 'ACKNOWLEDGED'",
                Long.class,
                alarmId);
        assertThat(historyRows).isEqualTo(1L);
        Long auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_operation_audit WHERE resource_public_id = ? AND action = 'alarm.acknowledge'",
                Long.class,
                alarmId);
        assertThat(auditRows).isEqualTo(1L);
        Long outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_outbox_event WHERE aggregate_public_id = ? AND event_type = 'alarm.acknowledged'",
                Long.class,
                alarmId);
        assertThat(outboxRows).isEqualTo(1L);

        // Re-ack is idempotent (returns existing acknowledged state, no new rows).
        AlarmCommandService.AcknowledgeResult second =
                commandService.acknowledge(new AlarmCommandService.AcknowledgeCommand(
                        alarmId,
                        result.version(),
                        2L,
                        "USER",
                        "Bob",
                        "re-check",
                        Instant.now(),
                        null,
                        "req_ack_2",
                        "127.0.0.1",
                        "test"));
        assertThat(second.status()).isEqualTo("ACKNOWLEDGED");
        Long auditRowsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_operation_audit WHERE resource_public_id = ? AND action = 'alarm.acknowledge'",
                Long.class,
                alarmId);
        assertThat(auditRowsAfter).isEqualTo(1L); // no new audit row on re-ack
    }

    @Test
    void recoveryConfirmationCommitsStateFactAuditAndOutbox() {
        String alarmId = seedFiringAlarm("fp-recovery-1", "NodeNotReady");
        jdbcTemplate.update("UPDATE koc_alarm_incident SET status = 'RECOVERY_PENDING' WHERE public_id = ?", alarmId);
        long version = repository.findByPublicId(alarmId).orElseThrow().version();
        Instant confirmedAt = Instant.parse("2026-07-20T02:00:00Z");

        AlarmCommandService.RecoveryConfirmationResult result =
                commandService.confirmRecovery(new AlarmCommandService.RecoveryConfirmationCommand(
                        alarmId,
                        version,
                        1L,
                        "USER",
                        "Alarm Command IT",
                        true,
                        "node health check passed",
                        confirmedAt,
                        "req_recovery_1",
                        "127.0.0.1",
                        "test"));

        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.version()).isEqualTo(version + 1);
        assertThat(repository.findByPublicId(alarmId).orElseThrow().status()).isEqualTo("RESOLVED");
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_alarm_recovery_confirmation WHERE incident_id = "
                                + "(SELECT id FROM koc_alarm_incident WHERE public_id = ?)",
                        alarmId))
                .isEqualTo(1L);
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_alarm_status_history WHERE incident_id = "
                                + "(SELECT id FROM koc_alarm_incident WHERE public_id = ?) "
                                + "AND to_status = 'RESOLVED' AND reason_code = 'MANUAL_RECOVERY_CONFIRMED'",
                        alarmId))
                .isEqualTo(1L);
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_operation_audit "
                                + "WHERE resource_public_id = ? AND action = 'alarm.recovery.confirm'",
                        alarmId))
                .isEqualTo(1L);
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_outbox_event "
                                + "WHERE aggregate_public_id = ? AND event_type = 'alarm.recovery.confirmed'",
                        alarmId))
                .isEqualTo(1L);
    }

    @Test
    void silenceApprovalCommitsStateFactAuditAndOutbox() {
        String alarmId = seedFiringAlarm("fp-silence-1", "NodeDiskHigh");
        long version = repository.findByPublicId(alarmId).orElseThrow().version();
        Instant approvedAt = Instant.parse("2026-07-20T02:10:00Z");
        Instant expiresAt = approvedAt.plusSeconds(1800);

        AlarmCommandService.SilenceApprovalResult result =
                commandService.approveSilence(new AlarmCommandService.SilenceApprovalCommand(
                        alarmId,
                        version,
                        1L,
                        "USER",
                        "Alarm Command IT",
                        "planned maintenance",
                        approvedAt,
                        expiresAt,
                        "req_silence_1",
                        "127.0.0.1",
                        "test"));

        assertThat(result.status()).isEqualTo("SUPPRESSED");
        assertThat(result.version()).isEqualTo(version + 1);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        assertThat(repository.findByPublicId(alarmId).orElseThrow().status()).isEqualTo("SUPPRESSED");
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_alarm_silence WHERE incident_id = "
                                + "(SELECT id FROM koc_alarm_incident WHERE public_id = ?) "
                                + "AND status = 'APPROVED'",
                        alarmId))
                .isEqualTo(1L);
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_operation_audit "
                                + "WHERE resource_public_id = ? AND action = 'alarm.silence.approve'",
                        alarmId))
                .isEqualTo(1L);
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_outbox_event "
                                + "WHERE aggregate_public_id = ? AND event_type = 'alarm.silence.approved'",
                        alarmId))
                .isEqualTo(1L);
    }

    @Test
    void auditFailureRollsBackIncidentAndCommandFact() {
        String alarmId = seedFiringAlarm("fp-rollback-1", "NodeNetworkUnavailable");
        long version = repository.findByPublicId(alarmId).orElseThrow().version();
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> jdbcProvider =
                (ObjectProvider<JdbcTemplate>) new SingletonObjectProvider<>(jdbcTemplate);
        @SuppressWarnings("unchecked")
        ObjectProvider<AlarmReadRepository> repoProvider =
                (ObjectProvider<AlarmReadRepository>) new SingletonObjectProvider<>(repository);
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> unavailableJdbcProvider =
                (ObjectProvider<JdbcTemplate>) new SingletonObjectProvider<JdbcTemplate>(null);
        AlarmCommandService rollbackService = transactionalAlarmCommandService(
                repoProvider,
                jdbcProvider,
                new OperationAuditWriter(unavailableJdbcProvider, new ObjectMapper()),
                new OutboxWriter(jdbcProvider, new ObjectMapper()),
                txManager);

        assertThatThrownBy(() -> rollbackService.acknowledge(new AlarmCommandService.AcknowledgeCommand(
                        alarmId,
                        version,
                        1L,
                        "USER",
                        "Alarm Command IT",
                        "must roll back",
                        Instant.parse("2026-07-20T02:20:00Z"),
                        null,
                        "req_rollback_1",
                        "127.0.0.1",
                        "test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Operation audit");

        AlarmIncidentRecordSnapshot current = current(alarmId);
        assertThat(current.status()).isEqualTo("FIRING");
        assertThat(current.version()).isEqualTo(version);
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_alarm_acknowledgement WHERE incident_id = "
                                + "(SELECT id FROM koc_alarm_incident WHERE public_id = ?)",
                        alarmId))
                .isZero();
        assertThat(count(
                        "SELECT COUNT(*) FROM koc_alarm_status_history WHERE incident_id = "
                                + "(SELECT id FROM koc_alarm_incident WHERE public_id = ?) "
                                + "AND to_status = 'ACKNOWLEDGED'",
                        alarmId))
                .isZero();
    }

    @Test
    void versionConflictSurfacesWhenIfMatchStale() {
        String alarmId = seedFiringAlarm("fp-ack-2", "NodeDiskFull");
        long version = repository.findByPublicId(alarmId).orElseThrow().version();
        // First ack bumps the version.
        commandService.acknowledge(new AlarmCommandService.AcknowledgeCommand(
                alarmId, version, 1L, "USER", "Alice", "first", Instant.now(), null, "req_v1", "127.0.0.1", "test"));
        // Stale If-Match (old version) must surface a version conflict.
        assertThatThrownBy(() -> commandService.acknowledge(new AlarmCommandService.AcknowledgeCommand(
                        alarmId,
                        version,
                        2L,
                        "USER",
                        "Bob",
                        "stale",
                        Instant.now(),
                        null,
                        "req_v2",
                        "127.0.0.1",
                        "test")))
                .isInstanceOf(AlarmCommandException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void acknowledgeNonFiringAlarmIsConflict() {
        String alarmId = seedFiringAlarm("fp-ack-3", "NodeMemHigh");
        // Manually resolve the incident so it is no longer FIRING.
        jdbcTemplate.update(
                "UPDATE koc_alarm_incident SET status = 'RESOLVED', resolved_at = ? WHERE public_id = ?",
                java.sql.Timestamp.from(Instant.now()),
                alarmId);
        long version = repository.findByPublicId(alarmId).orElseThrow().version();
        assertThatThrownBy(() -> commandService.acknowledge(new AlarmCommandService.AcknowledgeCommand(
                        alarmId,
                        version,
                        1L,
                        "USER",
                        "Alice",
                        "late",
                        Instant.now(),
                        null,
                        "req_resolved",
                        "127.0.0.1",
                        "test")))
                .isInstanceOf(AlarmCommandException.class)
                .extracting("code")
                .isEqualTo(AlarmCommandException.Code.CONFLICT);
    }

    @Test
    void idempotencyReplaysStoredResult() {
        String alarmId = seedFiringAlarm("fp-ack-4", "NodeInodeHigh");
        long version = repository.findByPublicId(alarmId).orElseThrow().version();
        IdempotencyService.IdempotencyScope scope = new IdempotencyService.IdempotencyScope(
                "USER", "usr_1", "POST:/api/v1/alarms/" + alarmId + "/acknowledgements");
        String canonical = "ack|" + alarmId + "|" + version + "|expanding";
        IdempotencyService.BeginResult first = idempotencyService.begin(scope, "key-1", canonical);
        assertThat(first.action()).isEqualTo(IdempotencyService.BeginResult.Action.EXECUTE);
        idempotencyService.succeed(
                scope, "key-1", 200, Map.of("alarmId", alarmId, "status", "ACKNOWLEDGED"), "alarm", alarmId);
        IdempotencyService.BeginResult repeat = idempotencyService.begin(scope, "key-1", canonical);
        assertThat(repeat.action()).isEqualTo(IdempotencyService.BeginResult.Action.REPLAY);
        assertThat(repeat.httpStatus()).isEqualTo(200);
    }

    @Test
    void idempotencyRejectsDifferentBodySameKey() {
        IdempotencyService.IdempotencyScope scope =
                new IdempotencyService.IdempotencyScope("USER", "usr_2", "POST:/api/v1/alarms/alm_x/acknowledgements");
        idempotencyService.begin(scope, "key-2", "body-A");
        IdempotencyService.BeginResult result = idempotencyService.begin(scope, "key-2", "body-B");
        assertThat(result.action()).isEqualTo(IdempotencyService.BeginResult.Action.REUSED);
    }

    private String seedFiringAlarm(String fingerprint, String alertName) {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "am-" + fingerprint,
                fingerprint,
                alertName,
                "alertmanager",
                "warning",
                AlarmSeverity.P2,
                AlarmResourceType.NODE,
                "worker-01",
                "prod",
                null,
                null,
                "metric",
                92.0,
                90.0,
                "%",
                "5m",
                Map.of(),
                Map.of(),
                null,
                AlarmStatus.FIRING,
                Instant.parse("2026-07-20T01:00:00Z"),
                alertName,
                Map.of());
        ActiveAlarmState state = new ActiveAlarmState(
                fingerprint,
                "am-" + fingerprint,
                alertName,
                "prod",
                null,
                null,
                "worker-01",
                AlarmSeverity.P2,
                AlarmStatus.FIRING,
                "policy-1",
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:10:00Z"),
                1);
        projection.project(event, AlarmEvaluationResult.unmatched(AlarmSeverity.P2, "seed"), state);
        Optional<Long> id = repository.findIdByPublicId(jdbcTemplate.queryForObject(
                "SELECT public_id FROM koc_alarm_incident WHERE fingerprint = ?", String.class, fingerprint));
        assertThat(id).as("seeded incident").isPresent();
        return jdbcTemplate.queryForObject(
                "SELECT public_id FROM koc_alarm_incident WHERE fingerprint = ?", String.class, fingerprint);
    }

    private long count(String sql, String alarmId) {
        return jdbcTemplate.queryForObject(sql, Long.class, alarmId);
    }

    private AlarmIncidentRecordSnapshot current(String alarmId) {
        return jdbcTemplate.queryForObject(
                "SELECT status, version FROM koc_alarm_incident WHERE public_id = ?",
                (rs, rowNum) -> new AlarmIncidentRecordSnapshot(rs.getString("status"), rs.getLong("version")),
                alarmId);
    }

    private record AlarmIncidentRecordSnapshot(String status, long version) {}

    private static AlarmCommandService transactionalAlarmCommandService(
            ObjectProvider<AlarmReadRepository> repoProvider,
            ObjectProvider<JdbcTemplate> jdbcProvider,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            org.springframework.transaction.PlatformTransactionManager txManager) {
        AlarmCommandService target = new AlarmCommandService(
                jdbcProvider, repoProvider, auditWriter, outboxWriter, idempotencyService, new ObjectMapper());
        org.springframework.transaction.interceptor.TransactionProxyFactoryBean proxy =
                new org.springframework.transaction.interceptor.TransactionProxyFactoryBean();
        proxy.setTarget(target);
        proxy.setTransactionManager(txManager);
        java.util.Properties attributes = new java.util.Properties();
        attributes.setProperty("*", "PROPAGATION_REQUIRED");
        proxy.setTransactionAttributes(attributes);
        proxy.afterPropertiesSet();
        return (AlarmCommandService) proxy.getObject();
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
