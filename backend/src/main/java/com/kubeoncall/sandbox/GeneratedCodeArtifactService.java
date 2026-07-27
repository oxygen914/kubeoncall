package com.kubeoncall.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.kubeoncall.sandbox.policy.SandboxToolSpec;

/** Saves an Agent-produced program as a bounded, untrusted input Artifact before it can be dispatched. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class GeneratedCodeArtifactService {

    static final int MAX_PROCESSES = 32;
    static final String OUTPUT_PATH = "/sandbox/output/result.json";

    private final SandboxArtifactStore artifactStore;
    private final SandboxRunRepository repository;
    private final SandboxToolCatalog toolCatalog;
    private final KubeOnCallProperties properties;
    private final ObjectMapper objectMapper;

    public GeneratedCodeArtifactService(
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

    /**
     * Writes {@code sandbox/{runId}/inputs/generated-code.json}. The body contains the source and
     * generation provenance; MySQL retains only the object reference, size and hash.
     */
    @Transactional
    public StoredGeneratedCode store(SandboxRunRecord run, Submission submission) {
        if (run == null || run.mode() != SandboxRunMode.GENERATED_CODE) {
            throw new IllegalArgumentException("generated code requires a GENERATED_CODE sandbox run");
        }
        validateSubmission(submission);
        List<String> admissionFindings = GeneratedCodeAdmission.inspect(submission.source());
        if (!admissionFindings.isEmpty()) {
            throw new GeneratedCodeRejectedException(admissionFindings);
        }
        SandboxToolSpec tool = toolCatalog
                .find(run.toolId(), run.toolVersion(), SandboxRunMode.GENERATED_CODE)
                .orElseThrow(() -> new IllegalArgumentException("generated runtime is not in the server catalog"));
        if (!submission.runtime().toolId().equals(tool.id())) {
            throw new IllegalArgumentException("submission runtime does not match the persisted sandbox tool");
        }
        byte[] source = submission.source().getBytes(StandardCharsets.UTF_8);
        if (source.length
                > Math.min(
                        properties.getSandbox().getScriptMaxBytes(),
                        tool.resourceLimits().scriptMaxBytes())) {
            throw new IllegalArgumentException("generated source exceeds the configured script ceiling");
        }
        byte[] payload = payload(submission, tool, sha256(source));
        SandboxArtifactStore.StoredArtifact artifact = artifactStore.store(
                run.publicId(),
                SandboxArtifactType.INPUT,
                "generated-code.json",
                "application/json",
                payload,
                SandboxClassification.UNTRUSTED);
        repository.createArtifact(new SandboxRunRepository.CreateArtifact(
                null,
                run.id(),
                SandboxArtifactType.INPUT,
                artifact.bucket(),
                artifact.objectKey(),
                artifact.contentType(),
                artifact.sizeBytes(),
                artifact.sha256(),
                SandboxClassification.UNTRUSTED,
                Instant.now().plus(Duration.ofHours(properties.getSandbox().getArtifactRetentionHours()))));
        return new StoredGeneratedCode(
                artifact.sha256(), sha256(source), submission.runtime(), SandboxClassification.UNTRUSTED);
    }

    private byte[] payload(Submission submission, SandboxToolSpec tool, String sourceSha256) {
        try {
            Map<String, Object> constraints = new LinkedHashMap<>();
            constraints.put("timeoutSeconds", tool.resourceLimits().timeoutSeconds());
            constraints.put("maxProcesses", MAX_PROCESSES);
            constraints.put("outputMaxBytes", tool.resourceLimits().outputMaxBytes());
            constraints.put("outputPath", OUTPUT_PATH);
            constraints.put("networkEgress", tool.networkEgressPolicy().name());
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", "v1");
            value.put("runtime", submission.runtime().wireValue());
            value.put("source", submission.source());
            value.put("sourceSha256", sourceSha256);
            value.put("model", submission.model());
            value.put("promptVersion", submission.promptVersion());
            value.put("generationReason", submission.generationReason());
            value.put("constraints", constraints);
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot encode generated code artifact", ex);
        }
    }

    private static void validateSubmission(Submission submission) {
        if (submission == null
                || submission.runtime() == null
                || blank(submission.source())
                || blank(submission.model())
                || blank(submission.promptVersion())
                || blank(submission.generationReason())) {
            throw new IllegalArgumentException(
                    "runtime, source, model, promptVersion and generationReason are required");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Runtime {
        PYTHON3("python3", "generated-python"),
        POSIX_SHELL("posix-shell", "generated-posix-shell");

        private final String wireValue;
        private final String toolId;

        Runtime(String wireValue, String toolId) {
            this.wireValue = wireValue;
            this.toolId = toolId;
        }

        public String wireValue() {
            return wireValue;
        }

        public String toolId() {
            return toolId;
        }
    }

    public record Submission(
            Runtime runtime, String source, String model, String promptVersion, String generationReason) {}

    public record StoredGeneratedCode(
            String artifactSha256, String sourceSha256, Runtime runtime, SandboxClassification classification) {}

    public static final class GeneratedCodeRejectedException extends IllegalArgumentException {
        private final List<String> findings;

        GeneratedCodeRejectedException(List<String> findings) {
            super("generated code rejected: " + findings);
            this.findings = List.copyOf(findings);
        }

        public List<String> findings() {
            return findings;
        }
    }
}
