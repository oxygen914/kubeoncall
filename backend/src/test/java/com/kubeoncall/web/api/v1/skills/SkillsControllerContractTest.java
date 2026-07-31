package com.kubeoncall.web.api.v1.skills;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.skill.SkillGovernanceException;
import com.kubeoncall.skill.SkillGovernanceService;
import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiException;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class SkillsControllerContractTest {

    private MockMvc mockMvc;
    private SkillGovernanceService service;
    private V1Security security;

    @BeforeEach
    void setUp() {
        service = mock(SkillGovernanceService.class);
        security = mock(V1Security.class);
        when(service.isAvailable()).thenReturn(true);
        when(security.requirePermission(PermissionCode.SKILL_MANAGE)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new SkillsController(provider(service), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void listUsesSkillReadAndUnifiedPageEnvelope() throws Exception {
        when(service.list(any())).thenReturn(new SkillStateRepository.SkillStatePage(java.util.List.of(state()), 1));

        mockMvc.perform(get("/api/v1/skills")
                        .param("enabled", "true")
                        .param("loadStatus", "LOADED")
                        .param("query", "node")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("diagnose-node"))
                .andExpect(jsonPath("$.data[0].name").value("Diagnose node"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].tags[0]").value("kubernetes"))
                .andExpect(jsonPath("$.data[0].applicableTasks[0]").value("QUERY_METRICS"))
                .andExpect(jsonPath("$.data[0].maxRisk").value("HIGH"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());

        verify(security).requirePermission(PermissionCode.SKILL_READ);
    }

    @Test
    void detailReturnsVersionEtag() throws Exception {
        when(service.find("diagnose-node")).thenReturn(state());

        mockMvc.perform(get("/api/v1/skills/diagnose-node"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.data.skillVersion").value("1.2.0"))
                .andExpect(jsonPath("$.data.sourceLocation").value("file:/skills/diagnose-node/SKILL.md"))
                .andExpect(jsonPath("$.data.triggers[0]").value("node alert"))
                .andExpect(jsonPath("$.data.services[0]").value("kubernetes"))
                .andExpect(jsonPath("$.data.allowedTools[0]").value("kubectl"))
                .andExpect(jsonPath("$.data.version").value(4));

        verify(security).requirePermission(PermissionCode.SKILL_READ);
    }

    @Test
    void enableCommandRequiresIfMatchAndReturnsNewEtag() throws Exception {
        when(service.setEnabled(any())).thenReturn(state(false, "DISABLED", 5));

        mockMvc.perform(post("/api/v1/skills/diagnose-node/enabled")
                        .header("If-Match", "\"4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.loadStatus").value("DISABLED"));

        verify(security).requirePermission(PermissionCode.SKILL_MANAGE);
    }

    @Test
    void invalidIfMatchUsesStableErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/skills/diagnose-node/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void reloadCreatesDurableTaskAndReturnsAccepted() throws Exception {
        when(service.requestReload(any())).thenReturn(task());

        mockMvc.perform(post("/api/v1/skills/reload"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskId").value("tsk_1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void versionConflictMapsToResourceVersionConflict() throws Exception {
        when(service.setEnabled(any()))
                .thenThrow(new SkillGovernanceException(
                        SkillGovernanceException.Code.VERSION_CONFLICT, "version changed"));

        mockMvc.perform(post("/api/v1/skills/diagnose-node/enabled")
                        .header("If-Match", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"));
    }

    @Test
    void readPermissionFailureUsesForbiddenEnvelope() throws Exception {
        when(security.requirePermission(PermissionCode.SKILL_READ)).thenThrow(V1ApiException.forbidden("no"));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static SkillStateRecord state() {
        return state(true, "LOADED", 4);
    }

    private static SkillStateRecord state(boolean enabled, String loadStatus, long version) {
        return new SkillStateRecord(
                "skl_1",
                "diagnose-node",
                "1.2.0",
                "a".repeat(64),
                "file:/skills/diagnose-node/SKILL.md",
                enabled,
                loadStatus,
                null,
                Map.of(
                        "name",
                        "Diagnose node",
                        "description",
                        "Diagnose Kubernetes node alerts",
                        "source",
                        "PROJECT",
                        "path",
                        "file:/skills/diagnose-node/SKILL.md",
                        "tags",
                        java.util.List.of("kubernetes", "node"),
                        "applicableTasks",
                        java.util.List.of("QUERY_METRICS"),
                        "maxRisk",
                        "HIGH",
                        "triggers",
                        java.util.List.of("node alert"),
                        "services",
                        java.util.List.of("kubernetes"),
                        "toolWhitelist",
                        java.util.List.of("kubectl")),
                Instant.parse("2026-07-21T02:00:00Z"),
                version,
                Instant.parse("2026-07-21T01:00:00Z"),
                Instant.parse("2026-07-21T02:00:00Z"));
    }

    private static AsyncTaskRecord task() {
        Instant now = Instant.parse("2026-07-21T03:00:00Z");
        return new AsyncTaskRecord(
                1,
                "tsk_1",
                "SKILL_RELOAD",
                "skill",
                "skill_registry",
                "skill-reload:1",
                "PENDING",
                "QUEUED",
                0,
                Map.of("requestedBy", 7L),
                Map.of(),
                null,
                null,
                null,
                null,
                0,
                0,
                5,
                now,
                null,
                null,
                "req_1",
                "trace_1",
                1,
                now,
                now);
    }

    private static V1Principal principal() {
        UserAccount user = new UserAccount(
                7,
                "usr_7",
                "operator",
                "Operator",
                "operator@example.com",
                "",
                "ARGON2ID",
                1,
                "ACTIVE",
                1,
                Instant.now(),
                null,
                null,
                0,
                Set.of("ADMIN"),
                Set.of(PermissionCode.SKILL_MANAGE, PermissionCode.SKILL_READ));
        return new V1Principal(
                user, Set.of(PermissionCode.SKILL_MANAGE, PermissionCode.SKILL_READ), V1Principal.AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
