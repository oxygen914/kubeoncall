package com.kubeoncall.sandbox;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * Persists a redacted remediation rehearsal specification. The Controller receives this only via a
 * short-lived artifact URL; production ConfigMap/Secret values and credentials are rejected before
 * they can enter either the validation-cluster namespace or object storage.
 */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class RemediationSimulationArtifactService {

    private static final int MAX_RESOURCES = 64;
    private static final int MAX_CHECKS = 32;

    private final SandboxArtifactStore artifactStore;
    private final SandboxRunRepository repository;
    private final SandboxToolCatalog toolCatalog;
    private final KubeOnCallProperties properties;
    private final ObjectMapper objectMapper;

    public RemediationSimulationArtifactService(
            SandboxArtifactStore artifactStore,
            SandboxRunRepository repository,
            SandboxToolCatalog toolCatalog,
            KubeOnCallProperties properties,
            ObjectMapper objectMapper) {
        this.artifactStore = artifactStore;
        this.repository = repository;
        this.toolCatalog = toolCatalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StoredSimulation store(SandboxRunRecord run, Submission submission) {
        if (run == null || run.mode() != SandboxRunMode.REMEDIATION_SIMULATION) {
            throw new IllegalArgumentException("remediation simulation requires a REMEDIATION_SIMULATION run");
        }
        validate(submission);
        toolCatalog
                .find(run.toolId(), run.toolVersion(), SandboxRunMode.REMEDIATION_SIMULATION)
                .orElseThrow(() -> new IllegalArgumentException("simulation runtime is not in the server catalog"));
        byte[] payload = encode(submission);
        if (payload.length > properties.getSandbox().getInputMaxBytes()) {
            throw new IllegalArgumentException("remediation simulation input exceeds configured input ceiling");
        }
        SandboxArtifactStore.StoredArtifact artifact = artifactStore.store(
                run.publicId(),
                SandboxArtifactType.INPUT,
                "remediation-simulation.json",
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
        return new StoredSimulation(
                artifact.sha256(), submission.redactedResources().size());
    }

    private byte[] encode(Submission submission) {
        try {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", "v1");
            value.put("redactedResources", submission.redactedResources());
            value.put("configPlaceholders", submission.configPlaceholders());
            value.put(
                    "checks",
                    Map.of(
                            "readiness", submission.readinessChecks(),
                            "startup", submission.startupChecks(),
                            "resourceState", submission.resourceStateChecks(),
                            "recoverySignals", submission.recoverySignals()));
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot encode remediation simulation Artifact", ex);
        }
    }

    private static void validate(Submission submission) {
        if (submission == null
                || submission.redactedResources() == null
                || submission.redactedResources().isEmpty()
                || submission.redactedResources().size() > MAX_RESOURCES
                || submission.configPlaceholders() == null) {
            throw new IllegalArgumentException("bounded redacted resources and config placeholders are required");
        }
        rejectSensitiveFields(submission.redactedResources());
        rejectSensitiveFields(submission.configPlaceholders());
        validateChecks(submission.readinessChecks(), "readiness");
        validateChecks(submission.startupChecks(), "startup");
        validateChecks(submission.resourceStateChecks(), "resourceState");
        validateChecks(submission.recoverySignals(), "recoverySignals");
    }

    private static void validateChecks(List<String> checks, String name) {
        if (checks == null
                || checks.size() > MAX_CHECKS
                || checks.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 256)) {
            throw new IllegalArgumentException(name + " checks must be bounded non-blank strings");
        }
    }

    @SuppressWarnings("unchecked")
    private static void rejectSensitiveFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (key.equals("data")
                        || key.equals("stringdata")
                        || key.contains("password")
                        || key.contains("token")
                        || key.contains("credential")) {
                    throw new IllegalArgumentException(
                            "simulation input must contain placeholders, not sensitive field: " + key);
                }
                rejectSensitiveFields(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                rejectSensitiveFields(item);
            }
        }
    }

    public record Submission(
            List<Map<String, Object>> redactedResources,
            Map<String, String> configPlaceholders,
            List<String> readinessChecks,
            List<String> startupChecks,
            List<String> resourceStateChecks,
            List<String> recoverySignals) {}

    public record StoredSimulation(String artifactSha256, int resourceCount) {}
}
