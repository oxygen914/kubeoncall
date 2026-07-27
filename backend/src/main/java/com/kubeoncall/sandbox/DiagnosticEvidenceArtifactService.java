package com.kubeoncall.sandbox;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;

/** Persists one canonical diagnostic evidence package at the Controller's fixed input location. */
@Service
public class DiagnosticEvidenceArtifactService {

    private final DiagnosticEvidenceBuilder builder;
    private final SandboxArtifactStore artifactStore;
    private final SandboxRunRepository repository;
    private final KubeOnCallProperties properties;

    public DiagnosticEvidenceArtifactService(
            DiagnosticEvidenceBuilder builder,
            SandboxArtifactStore artifactStore,
            SandboxRunRepository repository,
            KubeOnCallProperties properties) {
        this.builder = builder;
        this.artifactStore = artifactStore;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Stores only the redacted canonical package at {@code sandbox/{runId}/inputs/evidence.json}.
     * The database retains its reference/hash, never the evidence body.
     */
    @Transactional
    public StoredEvidence store(SandboxRunRecord run, DiagnosticEvidenceBuilder.BuildRequest request) {
        if (run == null) {
            throw new IllegalArgumentException("sandbox run is required");
        }
        DiagnosticEvidenceBuilder.EvidencePackage evidence = builder.build(request);
        SandboxArtifactStore.StoredArtifact artifact = artifactStore.store(
                run.publicId(),
                SandboxArtifactType.INPUT,
                "evidence.json",
                "application/json",
                evidence.content(),
                SandboxClassification.INTERNAL);
        repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                null,
                run.id(),
                SandboxArtifactType.INPUT,
                artifact.bucket(),
                artifact.objectKey(),
                artifact.contentType(),
                artifact.sizeBytes(),
                artifact.sha256(),
                SandboxClassification.INTERNAL,
                Instant.now().plus(Duration.ofHours(properties.getSandbox().getArtifactRetentionHours()))));
        return new StoredEvidence(artifact.sha256(), evidence.sha256(), evidence.classification());
    }

    public record StoredEvidence(String artifactSha256, String evidenceSha256, SandboxClassification classification) {}
}
