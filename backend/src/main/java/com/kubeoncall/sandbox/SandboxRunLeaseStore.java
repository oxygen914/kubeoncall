package com.kubeoncall.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/** Owns lease acquisition, release and heartbeat SQL for sandbox run workers and reconcilers. */
final class SandboxRunLeaseStore {

    private final JdbcTemplate jdbcTemplate;

    SandboxRunLeaseStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<Long> claim(String ownerToken, Instant now, Duration leaseDuration) {
        requireOwner(ownerToken);
        Instant leaseUntil = requireLease(now, leaseDuration);
        List<Long> candidates = jdbcTemplate.query("""
                SELECT id FROM koc_sandbox_run
                 WHERE run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND attempt < max_attempts
                   AND (
                     (run_status = 'PENDING' AND owner_token IS NULL)
                     OR (lease_until IS NOT NULL AND lease_until <= ?)
                   )
                 ORDER BY lease_until ASC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getLong("id"), now);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        long runId = candidates.get(0);
        int updated = jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET owner_token = ?,
                       lease_until = ?,
                       fencing_token = fencing_token + 1,
                       attempt = attempt + 1,
                       version = version + 1
                 WHERE id = ?
                   AND run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND attempt < max_attempts
                   AND (
                     (run_status = 'PENDING' AND owner_token IS NULL)
                     OR (lease_until IS NOT NULL AND lease_until <= ?)
                   )
                """, ownerToken, leaseUntil, runId, now);
        return updated == 1 ? Optional.of(runId) : Optional.empty();
    }

    boolean claimByPublicId(String publicId, String ownerToken, Instant now, Duration leaseDuration) {
        requireOwner(ownerToken);
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("publicId is required");
        }
        Instant leaseUntil = requireLease(now, leaseDuration);
        return jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET owner_token = ?,
                       lease_until = ?,
                       fencing_token = fencing_token + 1,
                       attempt = attempt + 1,
                       version = version + 1
                 WHERE public_id = ?
                   AND run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND attempt < max_attempts
                   AND (
                     (run_status = 'PENDING' AND owner_token IS NULL)
                     OR (lease_until IS NOT NULL AND lease_until <= ?)
                   )
                """, ownerToken, leaseUntil, publicId, now) == 1;
    }

    Optional<Long> claimForReconciliation(String ownerToken, Instant now, Duration leaseDuration) {
        requireOwner(ownerToken);
        Instant leaseUntil = requireLease(now, leaseDuration);
        List<Long> candidates = jdbcTemplate.query("""
                SELECT id FROM koc_sandbox_run
                 WHERE run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND (owner_token IS NULL OR lease_until <= ?)
                 ORDER BY updated_at ASC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getLong("id"), now);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        long runId = candidates.get(0);
        int updated = jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET owner_token = ?, lease_until = ?, fencing_token = fencing_token + 1, version = version + 1
                 WHERE id = ?
                   AND run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND (owner_token IS NULL OR lease_until <= ?)
                """, ownerToken, leaseUntil, runId, now);
        return updated == 1 ? Optional.of(runId) : Optional.empty();
    }

    Optional<Long> claimCleanupForReconciliation(String ownerToken, Instant now, Duration leaseDuration) {
        requireOwner(ownerToken);
        Instant leaseUntil = requireLease(now, leaseDuration);
        List<Long> candidates = jdbcTemplate.query("""
                SELECT id FROM koc_sandbox_run
                 WHERE run_status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND cleanup_status IN ('PENDING', 'RUNNING')
                   AND (owner_token IS NULL OR lease_until <= ?)
                 ORDER BY updated_at ASC, id ASC LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> rs.getLong("id"), now);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        long runId = candidates.get(0);
        int updated = jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET owner_token = ?, lease_until = ?, fencing_token = fencing_token + 1, version = version + 1
                 WHERE id = ?
                   AND run_status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                   AND cleanup_status IN ('PENDING', 'RUNNING')
                   AND (owner_token IS NULL OR lease_until <= ?)
                """, ownerToken, leaseUntil, runId, now);
        return updated == 1 ? Optional.of(runId) : Optional.empty();
    }

    boolean releaseReconciliationClaim(String publicId, String ownerToken, long fencingToken) {
        requireOwnership(ownerToken, fencingToken);
        return jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET owner_token = NULL, lease_until = NULL, version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND run_status NOT IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                """, publicId, ownerToken, fencingToken) == 1;
    }

    boolean releaseCleanupClaim(String publicId, String ownerToken, long fencingToken) {
        requireOwnership(ownerToken, fencingToken);
        return jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET owner_token = NULL, lease_until = NULL, version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND cleanup_status IN ('PENDING', 'RUNNING')
                """, publicId, ownerToken, fencingToken) == 1;
    }

    boolean heartbeat(String publicId, String ownerToken, long fencingToken, Instant now, Duration leaseDuration) {
        requireOwnership(ownerToken, fencingToken);
        Instant leaseUntil = requireLease(now, leaseDuration);
        return jdbcTemplate.update("""
                UPDATE koc_sandbox_run
                   SET lease_until = ?, version = version + 1
                 WHERE public_id = ?
                   AND owner_token = ?
                   AND fencing_token = ?
                   AND lease_until > ?
                """, leaseUntil, publicId, ownerToken, fencingToken, now) == 1;
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

    private static Instant requireLease(Instant now, Duration leaseDuration) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return now.plus(leaseDuration);
    }
}
