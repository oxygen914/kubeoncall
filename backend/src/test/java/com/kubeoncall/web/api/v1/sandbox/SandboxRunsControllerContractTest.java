package com.kubeoncall.web.api.v1.sandbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.sandbox.SandboxArtifactRecord;
import com.kubeoncall.sandbox.SandboxArtifactStore;
import com.kubeoncall.sandbox.SandboxRunCommandService;
import com.kubeoncall.sandbox.SandboxRunRecord;
import com.kubeoncall.sandbox.SandboxRunRepository;
import com.kubeoncall.sandbox.domain.SandboxArtifactType;
import com.kubeoncall.sandbox.domain.SandboxClassification;
import com.kubeoncall.sandbox.domain.SandboxCleanupStatus;
import com.kubeoncall.sandbox.domain.SandboxRiskLevel;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.domain.SandboxRunStatus;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Principal.AuthMethod;
import com.kubeoncall.web.api.v1.V1Security;

class SandboxRunsControllerContractTest {

    private MockMvc mockMvc;
    private SandboxRunCommandService commandService;
    private SandboxRunRepository repository;
    private SandboxArtifactStore artifactStore;
    private V1Security security;

    @BeforeEach
    void setUp() {
        commandService = mock(SandboxRunCommandService.class);
        repository = mock(SandboxRunRepository.class);
        artifactStore = mock(SandboxArtifactStore.class);
        security = mock(V1Security.class);
        when(commandService.isAvailable()).thenReturn(true);
        when(repository.isAvailable()).thenReturn(true);
        when(security.requirePermission(PermissionCode.SANDBOX_EXECUTE)).thenReturn(principal());
        when(security.requirePermission(PermissionCode.SANDBOX_CANCEL)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new SandboxRunsController(
                        provider(commandService), provider(repository), provider(artifactStore), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void createRequiresIdempotencyAndUsesSessionActor() throws Exception {
        when(commandService.create(any(), any(), eq("sandbox-create-0001")))
                .thenReturn(SandboxRunCommandService.CommandResult.executed(
                        Map.of("id", "sbx_1", "status", "PENDING"), 202));

        mockMvc.perform(post("/api/v1/sandbox-runs")
                        .header("Idempotency-Key", "sandbox-create-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"FIXED_DIAGNOSTIC","toolId":"pod-inspect","toolVersion":"v1",
                                 "expiresAt":"2026-08-01T00:00:00Z"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value("sbx_1"));

        ArgumentCaptor<SandboxRunCommandService.CreateCommand> command =
                ArgumentCaptor.forClass(SandboxRunCommandService.CreateCommand.class);
        verify(commandService).create(command.capture(), any(), eq("sandbox-create-0001"));
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorPublicId())
                .isEqualTo("usr_7");
    }

    @Test
    void cancelRequiresIfMatchVersion() throws Exception {
        mockMvc.perform(post("/api/v1/sandbox-runs/sbx_1/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void artifactEndpointNeverReturnsStorageLocation() throws Exception {
        when(repository.findByPublicId("sbx_1")).thenReturn(Optional.of(run()));
        when(repository.findArtifactsByRun(1L)).thenReturn(List.of(artifact()));

        mockMvc.perform(get("/api/v1/sandbox-runs/sbx_1/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("sba_1"))
                .andExpect(jsonPath("$.data[0].sha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.data[0].bucket").doesNotExist())
                .andExpect(jsonPath("$.data[0].objectKey").doesNotExist());
        verify(security).requirePermission(PermissionCode.SANDBOX_READ);
    }

    @Test
    void artifactDownloadIsShortLivedAndBoundToItsRun() throws Exception {
        when(repository.findByPublicId("sbx_1")).thenReturn(Optional.of(run()));
        when(repository.findArtifactByPublicId("sba_1")).thenReturn(Optional.of(artifact()));
        when(artifactStore.presignedGetUrl(
                        eq("private-sandbox-bucket"), eq("sandbox/sbx_1/reports/report.json"), any()))
                .thenReturn(new java.net.URL("https://minio.example/signed-artifact"));

        mockMvc.perform(get("/api/v1/sandbox-runs/sbx_1/artifacts/sba_1/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://minio.example/signed-artifact"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
        verify(security).requirePermission(PermissionCode.SANDBOX_READ);
    }

    private static SandboxRunRecord run() {
        return new SandboxRunRecord(
                1L,
                "sbx_1",
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
                "usr_7",
                "idempotency",
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                1,
                Instant.parse("2026-08-01T00:00:00Z"),
                "req_1",
                null,
                1,
                null,
                null,
                Instant.parse("2026-07-27T00:00:00Z"),
                Instant.parse("2026-07-27T00:00:00Z"));
    }

    private static SandboxArtifactRecord artifact() {
        return new SandboxArtifactRecord(
                2L,
                "sba_1",
                1L,
                SandboxArtifactType.REPORT,
                "private-sandbox-bucket",
                "sandbox/sbx_1/reports/report.json",
                "application/json",
                12L,
                "a".repeat(64),
                SandboxClassification.UNTRUSTED,
                Instant.parse("2026-07-28T00:00:00Z"),
                Instant.parse("2026-07-27T00:00:00Z"));
    }

    private static V1Principal principal() {
        UserAccount user = new UserAccount(
                7,
                "usr_7",
                "operator",
                "Operator",
                "operator@example.com",
                "hash",
                "argon2id",
                1,
                "ACTIVE",
                1,
                Instant.now(),
                null,
                null,
                0,
                Set.of("OPERATOR"),
                Set.of(PermissionCode.SANDBOX_EXECUTE, PermissionCode.SANDBOX_CANCEL, PermissionCode.SANDBOX_READ));
        return new V1Principal(user, user.permissions(), AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
