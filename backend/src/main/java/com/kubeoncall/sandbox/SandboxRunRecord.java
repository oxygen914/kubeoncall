package com.kubeoncall.sandbox;

import java.time.Instant;
import java.util.Map;

import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;

/**
 * Persisted snapshot of a sandbox run (§6.3). Body, script, full logs and large results are never
 * inlined here — {@code requestJson}/{@code resultJson} hold only summaries and references, with
 * payloads in MinIO addressed by artifact records. Ownership fields ({@code ownerToken},
 * {@code leaseUntil}, {@code fencingToken}, {@code version}) gate every reconciler write so a
 * paused owner whose lease expired cannot overwrite a successor's result.
 */
public record SandboxRunRecord(
        long id,
        String publicId,
        String executionPublicId,
        String alarmPublicId,
        SandboxRunMode mode,
        String toolId,
        String toolVersion,
        String runtimeImageDigest,
        SandboxRunStatus runStatus,
        SandboxCleanupStatus cleanupStatus,
        String stage,
        int progress,
        SandboxRiskLevel riskLevel,
        String requestedBy,
        String idempotencyKey,
        Map<String, Object> requestJson,
        Map<String, Object> resultJson,
        String errorCode,
        String errorSummary,
        String controllerRunId,
        String ownerToken,
        Instant leaseUntil,
        long fencingToken,
        int attempt,
        int maxAttempts,
        Instant expiresAt,
        String requestId,
        String traceId,
        long version,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {

    /** True when the run is in a terminal business state. */
    public boolean isTerminal() {
        return runStatus.isTerminal();
    }

    /** Convenience accessor used by the reconciler; classification lives on artifacts, not the run. */
    @SuppressWarnings("unused")
    public SandboxClassification defaultArtifactClassification() {
        return SandboxClassification.UNTRUSTED;
    }
}
