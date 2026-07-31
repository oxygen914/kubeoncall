package com.kubeoncall.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder.BuildRequest;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder.EvidenceItem;
import com.kubeoncall.sandbox.DiagnosticEvidenceBuilder.EvidenceType;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;

class DiagnosticEvidenceArtifactServiceTest {

    @Test
    void storesOnlyCanonicalRedactedInputArtifact() {
        SandboxArtifactStore store = mock(SandboxArtifactStore.class);
        SandboxRunRepository repository = mock(SandboxRunRepository.class);
        when(store.store(any(), any(), any(), any(), any(byte[].class), any()))
                .thenReturn(new SandboxArtifactStore.StoredArtifact(
                        "sandbox", "sandbox/sbx_abc/inputs/evidence.json", "application/json", 12, "a".repeat(64)));
        DiagnosticEvidenceArtifactService service = new DiagnosticEvidenceArtifactService(
                new DiagnosticEvidenceBuilder(new ObjectMapper()), store, repository, new KubeOnCallProperties());

        var result = service.store(
                run(),
                new BuildRequest(
                        "req_1",
                        null,
                        null,
                        Instant.parse("2026-07-27T12:00:00Z"),
                        List.of(new EvidenceItem(EvidenceType.LOG, "pod/a", "token=secret"))));

        assertThat(result.classification()).isEqualTo(SandboxClassification.INTERNAL);
        assertThat(result.artifactSha256()).isEqualTo("a".repeat(64));
        verify(store)
                .store(
                        org.mockito.ArgumentMatchers.eq("sbx_abc"),
                        org.mockito.ArgumentMatchers.eq(SandboxArtifactType.INPUT),
                        org.mockito.ArgumentMatchers.eq("evidence.json"),
                        org.mockito.ArgumentMatchers.eq("application/json"),
                        org.mockito.ArgumentMatchers.any(byte[].class),
                        org.mockito.ArgumentMatchers.eq(SandboxClassification.INTERNAL));
        verify(repository).createArtifact(any());
    }

    private static SandboxRunRecord run() {
        Instant now = Instant.parse("2026-07-27T12:00:00Z");
        return new SandboxRunRecord(
                1L,
                "sbx_abc",
                null,
                null,
                SandboxRunMode.FIXED_DIAGNOSTIC,
                "pod-inspect",
                "v1",
                "registry/tool@sha256:" + "a".repeat(64),
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
