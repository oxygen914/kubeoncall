package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStateException;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.sandbox.domain.SandboxStateMachine;

/**
 * Real-MySQL verification of {@link SandboxRunRepository}: idempotent create, CAS cancellation,
 * lease reclaim, fencing-token rejection of stale owners, and artifact reference lifecycle. Uses
 * Testcontainers like the other {@code *MySqlIT} suites; Failsafe runs it under the
 * {@code integration-test} profile.
 */
@Testcontainers
class SandboxRunRepositoryIT {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("kubeoncall")
            .withUsername("kubeoncall")
            .withPassword("test-password")
            .withReuse(false);

    private static SandboxRunRepository repository;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        String url = MYSQL.getJdbcUrl() + "?allowPublicKeyRetrieval=true&useSSL=false";
        DataSource dataSource = DataSourceBuilder.create()
                .url(url)
                .username(MYSQL.getUsername())
                .password(MYSQL.getPassword())
                .build();
        jdbcTemplate = new JdbcTemplate(dataSource);
        org.flywaydb.core.Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        repository = new SandboxRunRepository(jdbcTemplate, new ObjectMapper(), new SandboxStateMachine(), true);
    }

    private static SandboxRunRecord createRun(String idempotencyKey) {
        return createRunWithAttempts(idempotencyKey, 5);
    }

    private static SandboxRunRecord createRunWithAttempts(String idempotencyKey, int maxAttempts) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return repository.create(new SandboxRunRepository.CreateRun(
                null,
                "exec_" + suffix,
                "alm_" + suffix,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "log-analyzer",
                "1.0.0",
                "repo@sha256:" + "a".repeat(64),
                SandboxRiskLevel.LOW,
                "operator-1",
                idempotencyKey,
                Map.of("target", "pod/foo"),
                maxAttempts,
                Instant.now().plus(Duration.ofHours(1)),
                "req_" + suffix,
                "trace_" + suffix));
    }

    private static long fencingOf(SandboxRunRecord run) {
        return jdbcTemplate.queryForObject(
                "SELECT fencing_token FROM koc_sandbox_run WHERE public_id = ?", Long.class, run.publicId());
    }

    @Test
    void createShouldDeduplicateByModeAndIdempotencyKey() {
        String key = "idem-" + UUID.randomUUID();
        SandboxRunRecord first = createRun(key);
        SandboxRunRecord second = createRun(key);

        assertThat(second.publicId()).isEqualTo(first.publicId());
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.list(
                        new SandboxRunRepository.RunQuery(
                                SandboxRunMode.FIXED_DIAGNOSTIC, null, null, null, null, null),
                        0,
                        100))
                .extracting(SandboxRunRecord::publicId)
                .contains(first.publicId());
    }

    @Test
    void globalClaimShouldPickUpPendingRunAndAssignOwnership() {
        String key = "scan-" + UUID.randomUUID();
        createRun(key);

        // The global scanning claim picks up a freshly PENDING run (this one or another left in the
        // shared container) and assigns ownership with an advanced fencing token. The point is to
        // exercise the scanning path; per-run ownership is covered by claimByPublicId tests.
        SandboxRunRecord claimed = repository
                .claim("owner-scan", Instant.now(), Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(claimed.runStatus()).isEqualTo(SandboxRunStatus.PENDING);
        assertThat(claimed.ownerToken()).isEqualTo("owner-scan");
        assertThat(claimed.fencingToken()).isGreaterThan(0);
    }

    @Test
    void cancelShouldUseCasAndRejectTerminalRepeat() {
        String key = "cancel-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);
        Instant now = Instant.now();

        assertThat(repository.cancel(run.publicId(), run.version(), now)).isTrue();
        SandboxRunRecord cancelled = repository.findByPublicId(run.publicId()).orElseThrow();
        assertThat(cancelled.runStatus()).isEqualTo(SandboxRunStatus.CANCELLED);

        // A second cancel against the now-stale version is a no-op (terminal repeat).
        assertThat(repository.cancel(run.publicId(), run.version(), now)).isFalse();
    }

    @Test
    void claimShouldAdvanceFencingAndRejectStaleOwner() {
        String key = "reclaim-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);
        Instant now = Instant.now();

        // First owner claims the run.
        String ownerA = "owner-A";
        SandboxRunRecord claimed = repository
                .claimByPublicId(run.publicId(), ownerA, now, Duration.ofMillis(1))
                .orElseThrow();
        long fencingA = fencingOf(claimed);
        assertThat(fencingA).isGreaterThan(0);
        // Owner A advances to DISPATCHING (a legal transition) to prove its fencing token works.
        assertThat(repository.markDispatching(run.publicId(), ownerA, fencingA, claimed.version(), "ctrl-A", now))
                .isTrue();
        SandboxRunRecord dispatching = repository.findByPublicId(run.publicId()).orElseThrow();

        // Let the lease lapse, then a successor reclaims, advancing the fencing token.
        SandboxRunRecord reclaimed = repository
                .claimByPublicId(run.publicId(), "owner-B", now.plus(Duration.ofSeconds(5)), Duration.ofMinutes(1))
                .orElseThrow();
        long fencingB = fencingOf(reclaimed);
        assertThat(fencingB).isGreaterThan(fencingA);

        // The stale owner A attempts a legal DISPATCHING -> RUNNING transition, but its fencing token
        // no longer matches: the ownership predicate rejects the write rather than the state machine.
        assertThat(repository.transitionRunStatus(
                        run.publicId(), ownerA, fencingA, dispatching.version(), SandboxRunStatus.RUNNING, now))
                .isFalse();
    }

    @Test
    void transitionRunStatusShouldEnforceStateMachine() {
        String key = "trans-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);
        Instant now = Instant.now();

        // PENDING -> RUNNING is illegal (must dispatch first); the state machine rejects it before
        // any ownership check.
        assertThatThrownBy(() -> repository.transitionRunStatus(
                        run.publicId(), "owner", 1L, run.version(), SandboxRunStatus.RUNNING, now))
                .isInstanceOf(SandboxRunStateException.class);

        // Acquire ownership, then mark dispatching (legal PENDING -> DISPATCHING).
        SandboxRunRecord owned = repository
                .claimByPublicId(run.publicId(), "owner-T", now, Duration.ofMinutes(1))
                .orElseThrow();
        long fencing = fencingOf(owned);
        assertThat(repository.markDispatching(run.publicId(), "owner-T", fencing, owned.version(), "ctrl-1", now))
                .isTrue();
        SandboxRunRecord dispatching = repository.findByPublicId(run.publicId()).orElseThrow();
        assertThat(dispatching.runStatus()).isEqualTo(SandboxRunStatus.DISPATCHING);
        assertThat(dispatching.controllerRunId()).isEqualTo("ctrl-1");

        // DISPATCHING -> SUCCEEDED is illegal (must go through RUNNING/COLLECTING).
        assertThatThrownBy(() ->
                        repository.complete(run.publicId(), "owner-T", fencing, dispatching.version(), Map.of(), now))
                .isInstanceOf(SandboxRunStateException.class);
    }

    @Test
    void heartbeatShouldOnlyRenewOwnedLease() {
        String key = "hb-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);
        Instant now = Instant.now();

        SandboxRunRecord owned = repository
                .claimByPublicId(run.publicId(), "owner-H", now, Duration.ofMinutes(1))
                .orElseThrow();
        long fencing = fencingOf(owned);

        // A different owner cannot heartbeat.
        assertThat(repository.heartbeat(run.publicId(), "intruder", fencing, now, Duration.ofMinutes(2)))
                .isFalse();
        // The real owner can.
        assertThat(repository.heartbeat(run.publicId(), "owner-H", fencing, now, Duration.ofMinutes(2)))
                .isTrue();
    }

    @Test
    void cleanupStatusShouldNotRegressFromTerminal() {
        String key = "cleanup-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);

        assertThat(repository.transitionCleanupStatus(
                        run.publicId(), run.version(), SandboxCleanupStatus.PENDING, Instant.now()))
                .isTrue();
        SandboxRunRecord pending = repository.findByPublicId(run.publicId()).orElseThrow();
        assertThat(repository.transitionCleanupStatus(
                        run.publicId(), pending.version(), SandboxCleanupStatus.SUCCEEDED, Instant.now()))
                .isTrue();
        SandboxRunRecord succeeded = repository.findByPublicId(run.publicId()).orElseThrow();

        // SUCCEEDED is terminal: cannot move back to RUNNING.
        assertThatThrownBy(() -> repository.transitionCleanupStatus(
                        run.publicId(), succeeded.version(), SandboxCleanupStatus.RUNNING, Instant.now()))
                .isInstanceOf(SandboxRunStateException.class);
    }

    @Test
    void artifactsShouldBeListableAndExpirable() {
        String key = "art-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);
        Instant past = Instant.now().minus(Duration.ofMinutes(1));
        Instant future = Instant.now().plus(Duration.ofHours(24));

        repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                null,
                run.id(),
                SandboxArtifactType.INPUT,
                "sbx-bucket",
                "sandbox/" + run.publicId() + "/inputs/evidence.json",
                "application/json",
                2048L,
                "b".repeat(64),
                SandboxClassification.INTERNAL,
                future));
        repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                null,
                run.id(),
                SandboxArtifactType.LOG,
                "sbx-bucket",
                "sandbox/" + run.publicId() + "/logs/run.log",
                "text/plain",
                512L,
                "c".repeat(64),
                SandboxClassification.UNTRUSTED,
                past));

        List<SandboxArtifactRecord> artifacts = repository.findArtifactsByRun(run.id());
        assertThat(artifacts).hasSize(2);

        List<SandboxArtifactRecord> expired = repository.findExpiredArtifacts(Instant.now(), 100);
        assertThat(expired)
                .extracting(SandboxArtifactRecord::objectKey)
                .contains("sandbox/" + run.publicId() + "/logs/run.log");
    }

    @Test
    void claimShouldHonorMaxAttemptsAndStopReclaimingExhaustedRuns() {
        String key = "attempts-" + UUID.randomUUID();
        SandboxRunRecord run = createRunWithAttempts(key, 2);
        Instant now = Instant.now();

        // Two claims are permitted; each consumes one attempt.
        SandboxRunRecord first = repository
                .claimByPublicId(run.publicId(), "owner-1", now, Duration.ofMillis(1))
                .orElseThrow();
        assertThat(first.attempt()).isEqualTo(1);
        SandboxRunRecord second = repository
                .claimByPublicId(run.publicId(), "owner-2", now.plus(Duration.ofSeconds(5)), Duration.ofMillis(1))
                .orElseThrow();
        assertThat(second.attempt()).isEqualTo(2);

        // A third claim is rejected: the run has exhausted max_attempts and must not be retried again.
        assertThat(repository.claimByPublicId(
                        run.publicId(), "owner-3", now.plus(Duration.ofSeconds(10)), Duration.ofMinutes(1)))
                .as("exhausted run must not be reclaimed again")
                .isEmpty();
    }

    @Test
    void expiredOwnerCannotMutateRunBeforeReclaim() {
        String key = "lease-" + UUID.randomUUID();
        SandboxRunRecord run = createRun(key);
        Instant now = Instant.now();

        // Acquire ownership with a very short lease, then advance past it.
        SandboxRunRecord owned = repository
                .claimByPublicId(run.publicId(), "owner-L", now, Duration.ofMillis(1))
                .orElseThrow();
        long fencing = fencingOf(owned);
        Instant afterExpiry = now.plus(Duration.ofSeconds(5));

        // The owner's fencing token and version still match, but the lease has lapsed and no successor
        // has reclaimed yet. The transition must still be rejected: lease expiry revokes mutation
        // authority immediately, rather than only once another worker reclaims.
        assertThat(repository.markDispatching(
                        run.publicId(), "owner-L", fencing, owned.version(), "ctrl-L", afterExpiry))
                .as("expired-lease owner must not be able to mutate the run")
                .isFalse();
    }
}
