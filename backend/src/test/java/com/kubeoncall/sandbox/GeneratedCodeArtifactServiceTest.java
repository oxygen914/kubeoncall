package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.GeneratedCodeArtifactService.Runtime;
import com.kubeoncall.sandbox.GeneratedCodeArtifactService.Submission;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.sandbox.policy.SandboxResourceLimits;
import com.kubeoncall.sandbox.policy.SandboxToolCatalog;
import com.kubeoncall.sandbox.policy.SandboxToolSpec;

class GeneratedCodeArtifactServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void persistsProvenanceAndBoundedRuntimeConstraintsAsUntrustedArtifact() throws Exception {
        SandboxArtifactStore store = mock(SandboxArtifactStore.class);
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxToolCatalog catalog = mock(SandboxToolCatalog.class);
        SandboxToolSpec tool = pythonTool();
        when(catalog.find("generated-python", "v1", SandboxRunMode.GENERATED_CODE))
                .thenReturn(java.util.Optional.of(tool));
        when(store.store(any(), any(), any(), any(), any(byte[].class), any()))
                .thenReturn(new SandboxArtifactStore.StoredArtifact(
                        "sandbox",
                        "sandbox/sbx_abc/inputs/generated-code.json",
                        "application/json",
                        12,
                        "a".repeat(64)));
        GeneratedCodeArtifactService service =
                new GeneratedCodeArtifactService(store, repository, catalog, new KubeOnCallProperties(), objectMapper);

        var result = service.store(
                run("generated-python"),
                new Submission(Runtime.PYTHON3, "print('ok')", "model-a", "prompt-v3", "inspect restart cause"));

        assertThat(result.classification()).isEqualTo(SandboxClassification.UNTRUSTED);
        assertThat(result.sourceSha256()).hasSize(64);
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(store)
                .store(
                        eq("sbx_abc"),
                        eq(SandboxArtifactType.INPUT),
                        eq("generated-code.json"),
                        eq("application/json"),
                        payload.capture(),
                        eq(SandboxClassification.UNTRUSTED));
        JsonNode body = objectMapper.readTree(payload.getValue());
        assertThat(body.path("runtime").asText()).isEqualTo("python3");
        assertThat(body.path("model").asText()).isEqualTo("model-a");
        assertThat(body.path("promptVersion").asText()).isEqualTo("prompt-v3");
        assertThat(body.path("generationReason").asText()).isEqualTo("inspect restart cause");
        assertThat(body.path("constraints").path("maxProcesses").asInt()).isEqualTo(32);
        assertThat(body.path("constraints").path("outputPath").asText()).isEqualTo("/sandbox/output/result.json");
        assertThat(body.path("constraints").path("networkEgress").asText()).isEqualTo("DENY_ALL");
        verify(repository).createArtifact(any());
    }

    @Test
    void rejectsStaticDangerSignaturesBeforeAnyArtifactIsWritten() {
        SandboxArtifactStore store = mock(SandboxArtifactStore.class);
        GeneratedCodeArtifactService service = new GeneratedCodeArtifactService(
                store,
                mock(SandboxRunRepository.class),
                mock(SandboxToolCatalog.class),
                new KubeOnCallProperties(),
                objectMapper);

        assertThatThrownBy(() -> service.store(
                        run("generated-python"), new Submission(Runtime.PYTHON3, "while True: pass", "m", "p", "r")))
                .isInstanceOf(GeneratedCodeArtifactService.GeneratedCodeRejectedException.class)
                .hasMessageContaining("INFINITE_LOOP_SIGNATURE");
        assertThat(GeneratedCodeAdmission.inspect(":(){ :|:& };:")).contains("FORK_BOMB_SIGNATURE");
        assertThat(GeneratedCodeAdmission.inspect("curl https://example.invalid"))
                .contains("NETWORK_ATTEMPT");
        assertThat(GeneratedCodeAdmission.inspect("cat ../../etc/passwd")).contains("PATH_TRAVERSAL");
    }

    private static SandboxToolSpec pythonTool() {
        return new SandboxToolSpec(
                "generated-python",
                "v1",
                SandboxRunMode.GENERATED_CODE,
                "registry/generated-python@sha256:" + "d".repeat(64),
                "/usr/local/bin/koc-run-python",
                new SandboxResourceLimits("500m", "512Mi", "512Mi", 1, 1_048_576, 1, 262_144, 120, 300),
                SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                "sandbox-tools/schemas/generated-code-input-v1.json",
                "sandbox-tools/schemas/generated-code-result-v1.json");
    }

    private static SandboxRunRecord run(String toolId) {
        Instant now = Instant.parse("2026-07-27T12:00:00Z");
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                SandboxRunMode.GENERATED_CODE,
                toolId,
                "v1",
                "registry/tool@sha256:" + "d".repeat(64),
                SandboxRunStatus.PENDING,
                SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                SandboxRiskLevel.MEDIUM,
                "usr_1",
                "key",
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                3,
                now.plusSeconds(300),
                "req_1",
                null,
                1,
                null,
                null,
                now,
                now);
    }
}
