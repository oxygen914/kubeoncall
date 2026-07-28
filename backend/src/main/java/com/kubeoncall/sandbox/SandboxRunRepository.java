package com.kubeoncall.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 * MySQL repository for sandbox runs and their artifact references (§6.3, §6.4). Creation is
 * deduplicated by {@code (mode, idempotency_key)} and, when a duplicate is detected, the existing
 * run is returned rather than raised — so a retried dispatch never produces a second run. Every
 * mutating write after creation is guarded by the persisted {@code owner_token + fencing_token +
 * version} so a reconciler whose lease expired cannot overwrite a successor's result; terminal
 * states are additionally protected by the {@link SandboxStateMachine} transition table.
 *
 * <p>Bean-conditional on {@code kubeoncall.mysql-enabled=true}, matching every other MySQL fact
 * repository: when MySQL is off the sandbox capability stays dormant and this bean is absent.
 */
@Repository
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SandboxRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SandboxStateMachine stateMachine;
    private final SandboxArtifactMetadataStore artifactStore;
    private final SandboxRunLeaseStore leaseStore;
    private final boolean mysqlEnabled;

    public SandboxRunRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            SandboxStateMachine stateMachine,
            @Value("${kubeoncall.mysql-enabled:false}") boolean mysqlEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.stateMachine = stateMachine;
        this.artifactStore = new SandboxArtifactMetadataStore(jdbcTemplate);
        this.leaseStore = new SandboxRunLeaseStore(jdbcTemplate);
        this.mysqlEnabled = mysqlEnabled;
    }

    public boolean isAvailable() {
        return mysqlEnabled;
    }

    /**
     * Creates a run, or — when {@code (mode, idempotency_key)} already exists — returns the
     * previously created run. The create and its first artifact references share one transaction
     * so a run is never persisted without its declared inputs.
     */
    @Transactional
    public SandboxRunRecord create(CreateRun command) {
        String publicId = publicId(command.publicId());
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO koc_sandbox_run
                      (public_id, execution_public_id, alarm_public_id, mode, tool_id, tool_version,
                       runtime_image_digest, run_status, cleanup_status, risk_level, requested_by,
                       idempotency_key, request_json, max_attempts, expires_at, request_id, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'NOT_REQUIRED', ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    publicId,
                    command.executionPublicId(),
                    command.alarmPublicId(),
                    command.mode().name(),
                    command.toolId(),
                    command.toolVersion(),
                    command.runtimeImageDigest(),
                    command.riskLevel().name(),
                    command.requestedBy(),
                    command.idempotencyKey(),
                    json(command.requestJson()),
                    command.maxAttempts() <= 0 ? 1 : command.maxAttempts(),
                    command.expiresAt(),
                    command.requestId(),
                    command.traceId());
        } catch (DuplicateKeyException ex) {
            return findByModeAndIdempotencyKey(command.mode(), command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException(
                            "duplicate sandbox run not found after insert: " + command.idempotencyKey()));
        }
        return findByPublicId(publicId)
                .orElseThrow(() -> new IllegalStateException("Created sandbox run is not readable: " + publicId));
    }

    public Optional<SandboxRunRecord> findByPublicId(String publicId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + SandboxRunRowMapper.COLUMNS + " FROM koc_sandbox_run r WHERE r.public_id = ?",
                    new SandboxRunRowMapper(objectMapper),
                    publicId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<SandboxRunRecord> findByModeAndIdempotencyKey(SandboxRunMode mode, String idempotencyKey) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT "
                            + SandboxRunRowMapper.COLUMNS
                            + " FROM koc_sandbox_run r WHERE r.mode = ? AND r.idempotency_key = ?",
                    new SandboxRunRowMapper(objectMapper),
                    mode.name(),
                    idempotencyKey));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Page over runs matching the lifecycle query, oldest first. */
    public List<SandboxRunRecord> list(RunQuery query, int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        StringBuilder sql =
                new StringBuilder("SELECT " + SandboxRunRowMapper.COLUMNS + " FROM koc_sandbox_run r WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query.mode() != null) {
            sql.append(" AND r.mode = ?");
            args.add(query.mode().name());
        }
        if (query.runStatus() != null) {
            sql.append(" AND r.run_status = ?");
            args.add(query.runStatus().name());
        }
        if (query.executionPublicId() != null && !query.executionPublicId().isBlank()) {
            sql.append(" AND r.execution_public_id = ?");
            args.add(query.executionPublicId());
        }
        if (query.alarmPublicId() != null && !query.alarmPublicId().isBlank()) {
            sql.append(" AND r.alarm_public_id = ?");
            args.add(query.alarmPublicId());
        }
        if (query.createdFrom() != null) {
            sql.append(" AND r.created_at >= ?");
            args.add(query.createdFrom());
        }
        if (query.createdTo() != null) {
            sql.append(" AND r.created_at <= ?");
            args.add(query.createdTo());
        }
        sql.append(" ORDER BY r.created_at ASC, r.id ASC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), new SandboxRunRowMapper(objectMapper), args.toArray());
    }

    /**
     * Claims a run that is either freshly {@code PENDING} (no lease yet) or whose lease has expired,
     * advancing the fencing token and the attempt counter so the previous owner's writes are rejected
     * and the configured retry ceiling is honored. Newly created runs have a {@code NULL} lease and
     * are claimed here on first reconciliation; expired leases are reclaimed on subsequent passes.
     * Runs that have exhausted {@code max_attempts} are skipped so a repeatedly failing run cannot be
     * retried indefinitely. Mirrors {@code AsyncTaskRepository.claimNext}.
     */
    @Transactional
    public Optional<SandboxRunRecord> claim(String ownerToken, Instant now, Duration leaseDuration) {
        return leaseStore.claim(ownerToken, now, leaseDuration).flatMap(this::findById);
    }

    /**
     * Claims a specific run by public id (first claim or reclaim of an expired lease), advancing the
     * fencing token and the attempt counter. Used for targeted recovery of a known run; the global
     * {@link #claim} is the reconciler's scanning entry point. Honors {@code max_attempts} the same
     * way {@link #claim} does.
     */
    @Transactional
    public Optional<SandboxRunRecord> claimByPublicId(
            String publicId, String ownerToken, Instant now, Duration leaseDuration) {
        if (!leaseStore.claimByPublicId(publicId, ownerToken, now, leaseDuration)) {
            return Optional.empty();
        }
        return findByPublicId(publicId);
    }

    /**
     * Claims one non-terminal run for short reconciliation without consuming its dispatch-attempt
     * budget. The caller must release the claim after one Controller request so every Backend
     * instance can safely share polling work through the fencing token.
     */
    @Transactional
    public Optional<SandboxRunRecord> claimForReconciliation(String ownerToken, Instant now, Duration leaseDuration) {
        return leaseStore.claimForReconciliation(ownerToken, now, leaseDuration).flatMap(this::findById);
    }

    /** Releases a non-terminal reconciliation claim so the next short poll can be claimed promptly. */
    public boolean releaseReconciliationClaim(String publicId, String ownerToken, long fencingToken) {
        return leaseStore.releaseReconciliationClaim(publicId, ownerToken, fencingToken);
    }

    /** Claims one terminal Run whose Kubernetes cleanup still needs convergence. */
    @Transactional
    public Optional<SandboxRunRecord> claimCleanupForReconciliation(
            String ownerToken, Instant now, Duration leaseDuration) {
        return leaseStore
                .claimCleanupForReconciliation(ownerToken, now, leaseDuration)
                .flatMap(this::findById);
    }

    /** Releases a cleanup claim when the Controller is temporarily unavailable. */
    public boolean releaseCleanupClaim(String publicId, String ownerToken, long fencingToken) {
        return leaseStore.releaseCleanupClaim(publicId, ownerToken, fencingToken);
    }

    /** Renews the lease of a run the caller still owns. */
    public boolean heartbeat(
            String publicId, String ownerToken, long fencingToken, Instant now, Duration leaseDuration) {
        return leaseStore.heartbeat(publicId, ownerToken, fencingToken, now, leaseDuration);
    }

    /**
     * Applies a run-status transition after the state machine validates it is legal. Terminal
     * states are rejected by the machine before the SQL runs, and the {@code owner_token +
     * fencing_token + version} predicate prevents a stale owner from committing.
     */
    public boolean transitionRunStatus(
            String publicId,
            String ownerToken,
            long fencingToken,
            long expectedVersion,
            SandboxRunStatus target,
            Instant now) {
        requireOwnership(ownerToken, fencingToken);
        SandboxRunRecord current = findByPublicId(publicId)
                .orElseThrow(() -> new SandboxRunStateException("sandbox run not found: " + publicId));
        stateMachine.resolveRunStatus(current.runStatus(), target);
        return ownedUpdate(
                """
                UPDATE koc_sandbox_run
                   SET run_status = ?,
                       started_at = CASE WHEN ? = 'RUNNING' AND started_at IS NULL THEN ? ELSE started_at END,
                       finished_at = CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN ? ELSE finished_at END,
                       owner_token = CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN NULL ELSE owner_token END,
                       lease_until = CASE WHEN ? IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') THEN NULL ELSE lease_until END,
                       version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND version = ?
                   AND lease_until > ?
                """,
                target.name(),
                target.name(),
                now,
                target.name(),
                now,
                target.name(),
                target.name(),
                publicId,
                ownerToken,
                fencingToken,
                expectedVersion,
                now);
    }

    /** CAS-style cancellation: accepts the cancel only from a non-terminal state. */
    public boolean cancel(String publicId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET run_status = 'CANCELLED',
                       cleanup_status = CASE WHEN cleanup_status = 'NOT_REQUIRED' THEN 'PENDING' ELSE cleanup_status END,
                       finished_at = ?,
                       version = version + 1
                 WHERE public_id = ?
                   AND version = ?
                   AND run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                """, now, publicId, expectedVersion) == 1;
    }

    /** Records the controller-assigned run id and optional stage/progress under ownership guard. */
    public boolean markDispatching(
            String publicId,
            String ownerToken,
            long fencingToken,
            long expectedVersion,
            String controllerRunId,
            Instant now) {
        requireOwnership(ownerToken, fencingToken);
        SandboxRunRecord current = findByPublicId(publicId)
                .orElseThrow(() -> new SandboxRunStateException("sandbox run not found: " + publicId));
        stateMachine.resolveRunStatus(current.runStatus(), SandboxRunStatus.DISPATCHING);
        return ownedUpdate("""
                UPDATE koc_sandbox_run
                   SET run_status = 'DISPATCHING',
                       controller_run_id = ?,
                       started_at = COALESCE(started_at, ?),
                       version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND version = ?
                   AND lease_until > ?
                """, controllerRunId, now, publicId, ownerToken, fencingToken, expectedVersion, now);
    }

    public boolean updateProgress(
            String publicId, String ownerToken, long fencingToken, String stage, int progress, Instant now) {
        requireOwnership(ownerToken, fencingToken);
        if (progress < 0 || progress > 99) {
            throw new IllegalArgumentException("progress must be between 0 and 99");
        }
        return ownedUpdate("""
                UPDATE koc_sandbox_run
                   SET stage = ?, progress = ?, version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, stage, progress, publicId, ownerToken, fencingToken, now);
    }

    public boolean complete(
            String publicId,
            String ownerToken,
            long fencingToken,
            long expectedVersion,
            Map<String, Object> resultJson,
            Instant now) {
        requireOwnership(ownerToken, fencingToken);
        SandboxRunRecord current = findByPublicId(publicId)
                .orElseThrow(() -> new SandboxRunStateException("sandbox run not found: " + publicId));
        stateMachine.resolveRunStatus(current.runStatus(), SandboxRunStatus.SUCCEEDED);
        return ownedUpdate("""
                UPDATE koc_sandbox_run
                   SET run_status = 'SUCCEEDED',
                       progress = 100,
                       result_json = ?,
                       error_code = NULL,
                       error_summary = NULL,
                       finished_at = ?,
                       owner_token = NULL,
                       lease_until = NULL,
                       version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND version = ?
                   AND lease_until > ?
                """, json(resultJson), now, publicId, ownerToken, fencingToken, expectedVersion, now);
    }

    public boolean fail(
            String publicId,
            String ownerToken,
            long fencingToken,
            long expectedVersion,
            SandboxRunStatus terminal,
            String errorCode,
            String errorSummary,
            Instant now) {
        requireOwnership(ownerToken, fencingToken);
        if (terminal != SandboxRunStatus.FAILED && terminal != SandboxRunStatus.TIMED_OUT) {
            throw new IllegalArgumentException("terminal must be FAILED or TIMED_OUT");
        }
        SandboxRunRecord current = findByPublicId(publicId)
                .orElseThrow(() -> new SandboxRunStateException("sandbox run not found: " + publicId));
        stateMachine.resolveRunStatus(current.runStatus(), terminal);
        return ownedUpdate(
                """
                UPDATE koc_sandbox_run
                   SET run_status = ?,
                       error_code = ?,
                       error_summary = ?,
                       finished_at = ?,
                       owner_token = NULL,
                       lease_until = NULL,
                       version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND version = ?
                   AND lease_until > ?
                """,
                terminal.name(),
                errorCode,
                errorSummary,
                now,
                publicId,
                ownerToken,
                fencingToken,
                expectedVersion,
                now);
    }

    /** Transitions the cleanup lifecycle; terminal cleanup states cannot regress. */
    public boolean transitionCleanupStatus(
            String publicId, long expectedVersion, SandboxCleanupStatus target, Instant now) {
        SandboxRunRecord current = findByPublicId(publicId)
                .orElseThrow(() -> new SandboxRunStateException("sandbox run not found: " + publicId));
        stateMachine.resolveCleanupStatus(current.cleanupStatus(), target);
        return jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET cleanup_status = ?, version = version + 1
                 WHERE public_id = ?
                   AND version = ?
                """, target.name(), publicId, expectedVersion) == 1;
    }

    // -- Artifacts --------------------------------------------------------------

    /** Inserts an artifact reference; the object body must already be in MinIO. */
    public SandboxArtifactRecord createArtifact(CreateArtifact command) {
        return artifactStore.create(command);
    }

    public List<SandboxArtifactRecord> findArtifactsByRun(long sandboxRunId) {
        return artifactStore.findByRun(sandboxRunId);
    }

    public Optional<SandboxArtifactRecord> findArtifactByPublicId(String publicId) {
        return artifactStore.findByPublicId(publicId);
    }

    /** Artifact references past their retention, for the TTL janitor. */
    public List<SandboxArtifactRecord> findExpiredArtifacts(Instant now, int limit) {
        return artifactStore.findExpired(now, limit);
    }

    private Optional<SandboxRunRecord> findById(long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT " + SandboxRunRowMapper.COLUMNS + " FROM koc_sandbox_run r WHERE r.id = ?",
                    new SandboxRunRowMapper(objectMapper),
                    id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private boolean ownedUpdate(String sql, Object... args) {
        return jdbcTemplate.update(sql, args) == 1;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Sandbox run payload cannot be serialized", ex);
        }
    }

    private static void requireOwner(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken is required");
        }
    }

    private static void requireOwnership(String ownerToken, long fencingToken) {
        requireOwner(ownerToken);
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    private static String publicId(String requested) {
        return requested == null || requested.isBlank()
                ? "sbx_" + UUID.randomUUID().toString().replace("-", "")
                : requested;
    }

    /** Command to create a sandbox run. {@code idempotencyKey} deduplicates within {@code mode}. */
    public record CreateRun(
            String publicId,
            String executionPublicId,
            String alarmPublicId,
            SandboxRunMode mode,
            String toolId,
            String toolVersion,
            String runtimeImageDigest,
            SandboxRiskLevel riskLevel,
            String requestedBy,
            String idempotencyKey,
            Map<String, Object> requestJson,
            int maxAttempts,
            Instant expiresAt,
            String requestId,
            String traceId) {}

    /** Command to record an artifact reference for a run. */
    public record CreateArtifact(
            String publicId,
            long sandboxRunId,
            SandboxArtifactType artifactType,
            String bucket,
            String objectKey,
            String contentType,
            long sizeBytes,
            String sha256,
            SandboxClassification classification,
            Instant retentionUntil) {}

    /** Filter for {@link #list(RunQuery, int, int)}. */
    public record RunQuery(
            SandboxRunMode mode,
            SandboxRunStatus runStatus,
            String executionPublicId,
            String alarmPublicId,
            Instant createdFrom,
            Instant createdTo) {}
}
