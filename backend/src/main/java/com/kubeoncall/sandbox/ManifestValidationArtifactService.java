package com.kubeoncall.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.policy.SandboxToolCatalog;

/** Stores a manifest-validation request as an immutable input Artifact before a fixed runtime is dispatched. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class ManifestValidationArtifactService {

    static final String RULESET_VERSION = "sandbox-manifest-rules-v1";

    private final ManifestValidationService validator;
    private final SandboxArtifactStore artifactStore;
    private final SandboxRunRepository repository;
    private final SandboxToolCatalog toolCatalog;
    private final KubeOnCallProperties properties;
    private final ObjectMapper objectMapper;

    public ManifestValidationArtifactService(
            ManifestValidationService validator,
            SandboxArtifactStore artifactStore,
            SandboxRunRepository repository,
            SandboxToolCatalog toolCatalog,
            KubeOnCallProperties properties,
            ObjectMapper objectMapper) {
        this.validator = validator;
        this.artifactStore = artifactStore;
        this.repository = repository;
        this.toolCatalog = toolCatalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists the request and deterministic preflight report. Invalid input is retained for audit
     * but returned as {@code dispatchable=false}; callers must not create a Controller Job for it.
     */
    @Transactional
    public StoredValidation store(SandboxRunRecord run, ManifestValidationService.ValidationRequest request) {
        if (run == null || run.mode() != SandboxRunMode.MANIFEST_VALIDATION) {
            throw new IllegalArgumentException("manifest validation requires a MANIFEST_VALIDATION run");
        }
        toolCatalog
                .find(run.toolId(), run.toolVersion(), SandboxRunMode.MANIFEST_VALIDATION)
                .orElseThrow(() -> new IllegalArgumentException("manifest validator is not in the server catalog"));
        ManifestValidationService.ValidationResult preflight = validator.validate(request);
        byte[] payload = encode(request, preflight);
        if (payload.length > properties.getSandbox().getInputMaxBytes()) {
            throw new IllegalArgumentException("manifest validation input exceeds configured input ceiling");
        }
        SandboxArtifactStore.StoredArtifact artifact = artifactStore.store(
                run.publicId(),
                SandboxArtifactType.INPUT,
                "manifest-validation.json",
                "application/json",
                payload,
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
        return new StoredValidation(
                artifact.sha256(), preflight.valid(), preflight.findings().size(), RULESET_VERSION);
    }

    private byte[] encode(
            ManifestValidationService.ValidationRequest request, ManifestValidationService.ValidationResult preflight) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", "v1");
            value.put("type", request.type().name());
            value.put("document", request.document());
            if (request.snapshot() != null && !request.snapshot().isBlank()) {
                value.put("snapshot", request.snapshot());
            }
            value.put("rulesetVersion", RULESET_VERSION);
            value.put(
                    "preflight",
                    Map.of(
                            "valid", preflight.valid(),
                            "findings", preflight.findings(),
                            "toolVersions", preflight.toolVersions()));
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot encode manifest validation Artifact", ex);
        }
    }

    public record StoredValidation(
            String artifactSha256, boolean dispatchable, int findingCount, String rulesetVersion) {}
}
