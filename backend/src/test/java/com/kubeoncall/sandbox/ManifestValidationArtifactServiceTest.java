package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.sandbox.policy.SandboxResourceLimits;
import com.kubeoncall.sandbox.policy.SandboxToolCatalog;
import com.kubeoncall.sandbox.policy.SandboxToolSpec;

class ManifestValidationArtifactServiceTest {

    @Test
    void savesValidatedInputAndRulesetMetadataWithoutStorageLocationInTheResult() throws Exception {
        SandboxArtifactStore store = mock(SandboxArtifactStore.class);
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxToolCatalog catalog = mock(SandboxToolCatalog.class);
        when(catalog.find("manifest-validation", "v1", SandboxRunMode.MANIFEST_VALIDATION))
                .thenReturn(java.util.Optional.of(tool()));
        when(store.store(any(), any(), any(), any(), any(byte[].class), any()))
                .thenReturn(new SandboxArtifactStore.StoredArtifact(
                        "sandbox",
                        "sandbox/sbx_abc/inputs/manifest-validation.json",
                        "application/json",
                        12,
                        "a".repeat(64)));
        ObjectMapper mapper = new ObjectMapper();
        ManifestValidationArtifactService service = new ManifestValidationArtifactService(
                new ManifestValidationService(), store, repository, catalog, new KubeOnCallProperties(), mapper);

        var result = service.store(
                run(),
                new ManifestValidationService.ValidationRequest(
                        ManifestValidationService.Type.MANIFEST,
                        "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: api}\n",
                        null));

        assertThat(result.dispatchable()).isTrue();
        assertThat(result.rulesetVersion()).isEqualTo("sandbox-manifest-rules-v1");
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(store)
                .store(
                        eq("sbx_abc"),
                        eq(SandboxArtifactType.INPUT),
                        eq("manifest-validation.json"),
                        eq("application/json"),
                        body.capture(),
                        eq(SandboxClassification.INTERNAL));
        JsonNode payload = mapper.readTree(body.getValue());
        assertThat(payload.path("rulesetVersion").asText()).isEqualTo("sandbox-manifest-rules-v1");
        assertThat(payload.path("preflight").path("valid").asBoolean()).isTrue();
        verify(repository).createArtifact(any());
    }

    private static SandboxToolSpec tool() {
        return new SandboxToolSpec(
                "manifest-validation",
                "v1",
                SandboxRunMode.MANIFEST_VALIDATION,
                "registry/manifest-validation@sha256:" + "f".repeat(64),
                "/tool",
                new SandboxResourceLimits("500m", "512Mi", "1Gi", 1, 1_048_576, 1, 1, 180, 300),
                SandboxToolSpec.NetworkEgressPolicy.DENY_ALL,
                "input",
                "output");
    }

    private static SandboxRunRecord run() {
        Instant now = Instant.parse("2026-07-27T12:00:00Z");
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                SandboxRunMode.MANIFEST_VALIDATION,
                "manifest-validation",
                "v1",
                "registry/tool@sha256:" + "f".repeat(64),
                SandboxRunStatus.PENDING,
                SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                SandboxRiskLevel.LOW,
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
