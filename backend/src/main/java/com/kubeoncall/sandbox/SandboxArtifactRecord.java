package com.kubeoncall.sandbox;

import java.time.Instant;

import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;

/**
 * Persisted reference to a sandbox artifact (§6.3). The object body lives in MinIO at
 * {@code bucket}/{@code objectKey}; this record holds only the reference, size, SHA-256 and
 * classification so the database never stores untrusted payloads.
 */
public record SandboxArtifactRecord(
        long id,
        String publicId,
        long sandboxRunId,
        SandboxArtifactType artifactType,
        String bucket,
        String objectKey,
        String contentType,
        long sizeBytes,
        String sha256,
        SandboxClassification classification,
        Instant retentionUntil,
        Instant createdAt) {}
