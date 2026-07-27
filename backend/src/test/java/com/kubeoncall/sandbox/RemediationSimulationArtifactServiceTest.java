package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
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

class RemediationSimulationArtifactServiceTest {

    @Test
    void savesOnlyRedactedSimulationInputAndStructuredChecks() throws Exception {
        SandboxArtifactStore store = mock(SandboxArtifactStore.class);
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        SandboxToolCatalog catalog = mock(SandboxToolCatalog.class);
        when(catalog.find("remediation-simulation", "v1", SandboxRunMode.REMEDIATION_SIMULATION))
                .thenReturn(java.util.Optional.of(tool()));
        when(store.store(anyString(), any(), anyString(), anyString(), any(byte[].class), any()))
                .thenReturn(new SandboxArtifactStore.StoredArtifact(
                        "sandbox",
                        "sandbox/sbx_abc/inputs/remediation-simulation.json",
                        "application/json",
                        12,
                        "a".repeat(64)));
        ObjectMapper mapper = new ObjectMapper();
        RemediationSimulationArtifactService service = new RemediationSimulationArtifactService(
                store, repository, catalog, new KubeOnCallProperties(), mapper);

        var result = service.store(run(), submission());

        assertThat(result.resourceCount()).isEqualTo(1);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(store)
                .store(
                        eq("sbx_abc"),
                        eq(SandboxArtifactType.INPUT),
                        eq("remediation-simulation.json"),
                        eq("application/json"),
                        body.capture(),
                        eq(SandboxClassification.INTERNAL));
        JsonNode payload = mapper.readTree(body.getValue());
        assertThat(payload.path("redactedResources").get(0).path("kind").asText())
                .isEqualTo("Deployment");
        assertThat(payload.path("checks").path("recoverySignals").get(0).asText())
                .isEqualTo("ready-replicas=2");
        verify(repository).createArtifact(any());
    }

    @Test
    void rejectsSecretMaterialBeforeItCanReachObjectStorage() {
        RemediationSimulationArtifactService service = new RemediationSimulationArtifactService(
                mock(SandboxArtifactStore.class),
                mock(SandboxRunRepository.class),
                mock(SandboxToolCatalog.class),
                new KubeOnCallProperties(),
                new ObjectMapper());
        var leaking = new RemediationSimulationArtifactService.Submission(
                List.of(Map.of("kind", "Secret", "data", Map.of("password", "value"))),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> service.store(run(), leaking)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RemediationSimulationArtifactService.Submission submission() {
        return new RemediationSimulationArtifactService.Submission(
                List.of(Map.of("apiVersion", "apps/v1", "kind", "Deployment", "metadata", Map.of("name", "api"))),
                Map.of("DATABASE_URL", "SIMULATION_PLACEHOLDER"),
                List.of("deployment/api available"),
                List.of("containers started"),
                List.of("readyReplicas=2"),
                List.of("ready-replicas=2"));
    }

    private static SandboxToolSpec tool() {
        return new SandboxToolSpec(
                "remediation-simulation",
                "v1",
                SandboxRunMode.REMEDIATION_SIMULATION,
                "registry/simulation@sha256:" + "1".repeat(64),
                "/simulate",
                new SandboxResourceLimits("500m", "512Mi", "1Gi", 1, 1_048_576, 1, 1, 300, 600),
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
                SandboxRunMode.REMEDIATION_SIMULATION,
                "remediation-simulation",
                "v1",
                "registry/simulation@sha256:" + "1".repeat(64),
                SandboxRunStatus.PENDING,
                SandboxCleanupStatus.NOT_REQUIRED,
                null,
                0,
                SandboxRiskLevel.HIGH,
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
