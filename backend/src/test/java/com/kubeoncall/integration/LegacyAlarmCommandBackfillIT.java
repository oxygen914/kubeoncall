package com.kubeoncall.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.approval.RedisApprovalRepository;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.IdentityRepository;
import com.kubeoncall.migration.ApprovalBackfillRunner;
import com.kubeoncall.migration.LegacyActorResolver;
import com.kubeoncall.migration.LegacyAlarmCommandBackfillRunner;
import com.kubeoncall.migration.LegacyExecutionAuditBackfillRunner;
import com.kubeoncall.migration.MigrationLedgerRepository;

/** Verifies source claims make a Redis command backfill safe to replay after checkpoint loss. */
@Testcontainers
class LegacyAlarmCommandBackfillIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static JdbcTemplate jdbcTemplate;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redis;
    private LegacyAlarmCommandBackfillRunner runner;
    private LegacyExecutionAuditBackfillRunner executionAuditRunner;
    private ApprovalBackfillRunner approvalBackfillRunner;
    private String key;
    private String acknowledgementKey;
    private String executionAuditKey;
    private String approvalKey;
    private String approvalExecutionId;
    private String fingerprint;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = DataSourceBuilder.create()
                .url(MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false")
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway.configure()
                .dataSource(
                        MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false",
                        MYSQL.getUsername(),
                        MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "DELETE FROM koc_migration_scan_checkpoint WHERE domain IN ('alarm-ack', 'alarm-silence', 'alarm-recovery', 'execution-audit')");
        redisConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redis = new StringRedisTemplate(redisConnectionFactory);
        redis.afterPropertiesSet();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        fingerprint = "fp-backfill-" + suffix;
        key = "alarm-ack:" + fingerprint;
        acknowledgementKey = key;
        long userId = insertUser(suffix);
        insertIncident(suffix);
        approvalExecutionId = "exe_ap_" + suffix;
        insertExecution(approvalExecutionId, suffix, userId);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setBackfillDryRun(false);
        properties.getDataMigration().setBackfillBatchSize(10);
        IdentityRepository identities = new IdentityRepository(jdbcTemplate, true);
        LegacyActorResolver resolver = new LegacyActorResolver(new SingletonObjectProvider<>(identities), properties);
        MigrationLedgerRepository ledger = new MigrationLedgerRepository(new SingletonObjectProvider<>(jdbcTemplate));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        runner = new LegacyAlarmCommandBackfillRunner(
                new SingletonObjectProvider<>(redis),
                new SingletonObjectProvider<>(jdbcTemplate),
                new SingletonObjectProvider<>(ledger),
                resolver,
                objectMapper,
                properties);
        executionAuditRunner = new LegacyExecutionAuditBackfillRunner(
                new SingletonObjectProvider<>(redis),
                new SingletonObjectProvider<>(ledger),
                new OperationAuditWriter(new SingletonObjectProvider<>(jdbcTemplate), objectMapper),
                objectMapper,
                properties);
        approvalBackfillRunner = new ApprovalBackfillRunner(
                new SingletonObjectProvider<>(redis),
                new SingletonObjectProvider<>(new RedisApprovalRepository(redis, objectMapper, properties)),
                new SingletonObjectProvider<>(new MySqlApprovalRepository(jdbcTemplate, objectMapper, true)),
                new SingletonObjectProvider<>(ledger),
                properties);
        redis.opsForValue().set(key, """
                {"fingerprint":"%s","acknowledgedBy":"operator-%s","reason":"legacy acknowledgement","acknowledgedAt":"2026-07-22T00:00:00Z","expiresAt":"2027-07-22T01:00:00Z"}
                """.formatted(fingerprint, suffix));
        assertThat(userId).isPositive();
    }

    @AfterEach
    void tearDown() {
        if (redis != null && key != null) {
            redis.delete(key);
        }
        if (redis != null && acknowledgementKey != null && !acknowledgementKey.equals(key)) {
            redis.delete(acknowledgementKey);
        }
        if (redis != null && executionAuditKey != null) {
            redis.delete(executionAuditKey);
        }
        if (redis != null && approvalKey != null) {
            redis.delete(approvalKey);
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    void applyIsIdempotentWhenACompletedScanMustReplayAfterCheckpointLoss() {
        LegacyAlarmCommandBackfillRunner.BackfillResult first =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.ACKNOWLEDGEMENT, false, "req-first");

        assertThat(first.migrated()).isEqualTo(1);
        assertThat(commandCount()).isEqualTo(1);
        assertThat(claimCount()).isEqualTo(1);
        assertThat(incidentStatus()).isEqualTo("ACKNOWLEDGED");

        jdbcTemplate.update("DELETE FROM koc_migration_scan_checkpoint WHERE domain = 'alarm-ack'");

        LegacyAlarmCommandBackfillRunner.BackfillResult replay =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.ACKNOWLEDGEMENT, false, "req-replay");

        assertThat(replay.skipped()).isEqualTo(1);
        assertThat(commandCount()).isEqualTo(1);
        assertThat(claimCount()).isEqualTo(1);
    }

    @Test
    void silenceApplyIsIdempotentWhenACompletedScanMustReplayAfterCheckpointLoss() {
        key = "alarm-silence-approval:" + fingerprint;
        redis.opsForValue().set(key, """
                {"fingerprint":"%s","approvedBy":"operator-%s","reason":"legacy silence","approvedAt":"2026-07-22T00:00:00Z","expiresAt":"2027-07-22T01:00:00Z"}
                """.formatted(fingerprint, fingerprintSuffix()));

        LegacyAlarmCommandBackfillRunner.BackfillResult first =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.SILENCE, false, "req-silence-first");

        assertThat(first.migrated()).isEqualTo(1);
        assertThat(silenceCount()).isEqualTo(1);
        assertThat(claimCount("alarm-silence")).isEqualTo(1);
        assertThat(incidentStatus()).isEqualTo("SUPPRESSED");

        jdbcTemplate.update("DELETE FROM koc_migration_scan_checkpoint WHERE domain = 'alarm-silence'");

        LegacyAlarmCommandBackfillRunner.BackfillResult replay =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.SILENCE, false, "req-silence-replay");

        assertThat(replay.skipped()).isEqualTo(1);
        assertThat(silenceCount()).isEqualTo(1);
        assertThat(claimCount("alarm-silence")).isEqualTo(1);
    }

    @Test
    void expiredLegacySilenceDoesNotReviveAnIncident() {
        key = "alarm-silence-approval:" + fingerprint;
        redis.opsForValue().set(key, """
                {"fingerprint":"%s","approvedBy":"operator-%s","reason":"expired legacy silence","approvedAt":"2025-07-22T00:00:00Z","expiresAt":"2025-07-22T01:00:00Z"}
                """.formatted(fingerprint, fingerprintSuffix()));

        LegacyAlarmCommandBackfillRunner.BackfillResult result =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.SILENCE, false, "req-expired-silence");

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(silenceCount()).isZero();
        assertThat(claimCount("alarm-silence")).isZero();
        assertThat(incidentStatus()).isEqualTo("FIRING");
    }

    @Test
    void confirmedRecoveryApplyIsIdempotentWhenACompletedScanMustReplayAfterCheckpointLoss() {
        jdbcTemplate.update(
                "UPDATE koc_alarm_incident SET status = 'RECOVERY_PENDING' WHERE fingerprint = ?", fingerprint);
        key = "alarm-recovery:" + fingerprint;
        redis.opsForValue().set(key, """
                {"fingerprint":"%s","confirmedBy":"operator-%s","note":"legacy recovery","confirmedAt":"2026-07-22T00:00:00Z","status":"CONFIRMED","healthCheckPassed":true}
                """.formatted(fingerprint, fingerprintSuffix()));

        LegacyAlarmCommandBackfillRunner.BackfillResult first =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.RECOVERY, false, "req-recovery-first");

        assertThat(first.migrated()).isEqualTo(1);
        assertThat(recoveryCount()).isEqualTo(1);
        assertThat(claimCount("alarm-recovery")).isEqualTo(1);
        assertThat(incidentStatus()).isEqualTo("RESOLVED");

        jdbcTemplate.update("DELETE FROM koc_migration_scan_checkpoint WHERE domain = 'alarm-recovery'");

        LegacyAlarmCommandBackfillRunner.BackfillResult replay =
                runner.run(LegacyAlarmCommandBackfillRunner.Kind.RECOVERY, false, "req-recovery-replay");

        assertThat(replay.skipped()).isEqualTo(1);
        assertThat(recoveryCount()).isEqualTo(1);
        assertThat(claimCount("alarm-recovery")).isEqualTo(1);
    }

    @Test
    void executionAuditApplyIsIdempotentWhenACompletedScanMustReplayAfterCheckpointLoss() {
        String executionId = "exe_" + fingerprintSuffix();
        executionAuditKey = "execution-audit:" + executionId + ":1721606400000";
        redis.opsForValue().set(executionAuditKey, """
                {"executionId":"%s","requestType":"ASK","status":"SUCCESS","approvalRequired":false,"autoHandled":true,"durationMs":42,"summary":"legacy audit","failureReason":null,"tools":["tool-a"],"occurredAt":"2026-07-22T00:00:00Z","retryCount":0,"replanRequired":false,"degraded":false,"approvalLatencyMs":0,"toolSuccessCount":1,"toolFailureCount":0,"metadata":{"legacy":true}}
                """.formatted(executionId));

        LegacyExecutionAuditBackfillRunner.BackfillResult first = executionAuditRunner.run(false, "req-audit-first");

        assertThat(first.migrated()).isEqualTo(1);
        assertThat(operationAuditCount(executionId)).isEqualTo(1);
        assertThat(sourceClaimCount("execution-audit", executionAuditKey)).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM koc_migration_scan_checkpoint WHERE domain = 'execution-audit'");

        LegacyExecutionAuditBackfillRunner.BackfillResult replay = executionAuditRunner.run(false, "req-audit-replay");

        assertThat(replay.skipped()).isEqualTo(1);
        assertThat(operationAuditCount(executionId)).isEqualTo(1);
        assertThat(sourceClaimCount("execution-audit", executionAuditKey)).isEqualTo(1);
    }

    @Test
    void approvalApplyPersistsTheRemainingRedisTtlAsMySqlExpiresAt() {
        approvalKey = "approval-request:" + approvalExecutionId;
        Instant requestedAt = Instant.now().minusSeconds(10);
        redis.opsForValue()
                .set(
                        approvalKey,
                        """
                {"executionId":"%s","taskPlan":null,"taskId":"task-approval","requestedBy":"%d","decision":null,"requestedAt":"%s","decidedAt":null,"comment":null,"decidedBy":null,"processed":false,"riskReasons":[]}
                """.formatted(approvalExecutionId, userIdForApproval(), requestedAt),
                        Duration.ofSeconds(90));
        Instant before = Instant.now();

        ApprovalBackfillRunner.BackfillResult result = approvalBackfillRunner.run(false, "req-approval-ttl");
        Instant after = Instant.now();
        Instant expiresAt = jdbcTemplate.queryForObject(
                "SELECT a.expires_at FROM koc_approval_request a JOIN koc_workflow_execution e ON e.id = a.execution_id WHERE e.public_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                approvalExecutionId);

        assertThat(result.migrated()).isEqualTo(1);
        assertThat(expiresAt).isBetween(before.plusSeconds(85), after.plusSeconds(95));
        assertThat(expiresAt).isAfter(requestedAt);
    }

    private long insertUser(String suffix) {
        jdbcTemplate.update(
                "INSERT INTO koc_user (public_id, username, username_normalized, display_name, password_hash, password_algorithm) VALUES (?, ?, ?, ?, 'hash', 'BCRYPT')",
                "usr_" + suffix,
                "operator-" + suffix,
                "operator-" + suffix,
                "Operator");
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private void insertIncident(String suffix) {
        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_incident
                  (public_id, fingerprint, cycle_no, alert_name, severity, severity_rank, status,
                   resource_type, resource_name, first_seen, last_seen)
                VALUES (?, ?, 1, 'LegacyAck', 'P2', 2, 'FIRING', 'NODE', 'worker-01', ?, ?)
                """,
                "alm_" + suffix,
                fingerprint,
                java.sql.Timestamp.from(Instant.parse("2026-07-21T23:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-07-21T23:00:00Z")));
    }

    private void insertExecution(String executionPublicId, String suffix, long userId) {
        jdbcTemplate.update("""
                INSERT INTO koc_workflow_execution
                  (public_id, type, trigger_type, dedupe_key, status, risk_level, actor_type, actor_id, request_id)
                VALUES (?, 'ASK', 'MANUAL', ?, 'WAITING_APPROVAL', 'MEDIUM', 'USER', ?, ?)
                """, executionPublicId, "approval-backfill-" + suffix, userId, "req-approval-" + suffix);
    }

    private long userIdForApproval() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT actor_id FROM koc_workflow_execution WHERE public_id = ?", Long.class, approvalExecutionId);
        return userId == null ? 0L : userId;
    }

    private long commandCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM koc_alarm_acknowledgement", Long.class);
        return count == null ? 0L : count;
    }

    private long claimCount() {
        return claimCount("alarm-ack");
    }

    private long claimCount(String domain) {
        return sourceClaimCount(domain, key);
    }

    private long sourceClaimCount(String domain, String sourceKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_migration_source_claim WHERE domain = ? AND source_key = ?",
                Long.class,
                domain,
                sourceKey);
        return count == null ? 0L : count;
    }

    private long operationAuditCount(String executionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_operation_audit WHERE resource_type = 'WORKFLOW_EXECUTION' AND resource_public_id = ?",
                Long.class,
                executionId);
        return count == null ? 0L : count;
    }

    private long silenceCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_silence s JOIN koc_alarm_incident i ON i.id = s.incident_id WHERE i.fingerprint = ?",
                Long.class,
                fingerprint);
        return count == null ? 0L : count;
    }

    private long recoveryCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM koc_alarm_recovery_confirmation r JOIN koc_alarm_incident i ON i.id = r.incident_id WHERE i.fingerprint = ?",
                Long.class,
                fingerprint);
        return count == null ? 0L : count;
    }

    private String fingerprintSuffix() {
        return fingerprint.substring("fp-backfill-".length());
    }

    private String incidentStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM koc_alarm_incident WHERE fingerprint = ?", String.class, fingerprint);
    }

    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {

        private final T value;

        private SingletonObjectProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }
    }
}
