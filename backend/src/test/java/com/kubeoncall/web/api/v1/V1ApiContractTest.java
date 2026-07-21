package com.kubeoncall.web.api.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.alarm.readmodel.AlarmQueryService;
import com.kubeoncall.alarm.readmodel.AlarmTimelineItem;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.CsrfService;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.web.api.v1.alarms.AlarmsController;
import com.kubeoncall.web.api.v1.auth.AuthController;
import com.kubeoncall.web.api.v1.capabilities.CapabilitiesController;
import com.kubeoncall.web.api.v1.capabilities.CapabilitiesService;
import com.kubeoncall.web.api.v1.dictionaries.DictionariesController;
import com.kubeoncall.web.api.v1.system.SystemStatusController;

/**
 * HTTP-level contract tests for the {@code /api/v1} surface: response envelope shape, status codes,
 * auth enforcement and error codes. Uses a standalone MockMvc with the request-id and authentication
 * filters wired manually, so the slice behaves like production without pulling the legacy
 * {@code /api} filters (which need metrics and Redis beans unavailable in a slice).
 *
 * <p>Asserts what the frontend actually receives: the {@code {data, meta}} / {@code {error, meta}}
 * envelope, 401 vs 503, stable error codes, and the echoed {@code X-Request-Id}. Authenticated
 * requests are staged by seeding the {@link SecurityContextHolder} with a {@link V1AuthenticationToken}
 * before each call.
 */
class V1ApiContractTest {

    private MockMvc mockMvc;

    private CapabilitiesService capabilitiesService;
    private AlarmQueryService alarmQueryService;
    private V1Security security;

    @BeforeEach
    void setUp() {
        capabilitiesService = mock(CapabilitiesService.class);
        alarmQueryService = mock(AlarmQueryService.class);
        security = mock(V1Security.class);
        // By default the mocked security guard reports anonymous, so endpoints that require auth
        // surface 401/403. Individual tests stub requirePermission/hasPermission to "authorize".
        when(security.currentPrincipal()).thenReturn(V1Principal.anonymous());
        AuthService authService = newAuthService();
        V1AuthenticationFilter authFilter = new V1AuthenticationFilter(authService, new KubeOnCallProperties(), true);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new SystemStatusController(),
                        new CapabilitiesController(capabilitiesService),
                        new DictionariesController(),
                        new AuthController(authService, new KubeOnCallProperties()),
                        new AlarmsController(alarmQueryService, security))
                .addFilters(new RequestIdFilter())
                .addFilter(authFilter)
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void systemStatusReturnsUnifiedEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/system/status").header("X-Request-Id", "req_abcdef0123456789abcdef0123456789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service").value("KubeOnCall"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.meta.requestId").value("req_abcdef0123456789abcdef0123456789"))
                .andExpect(jsonPath("$.meta.timestamp").exists())
                .andExpect(header().string("X-Request-Id", "req_abcdef0123456789abcdef0123456789"));
    }

    @Test
    void capabilitiesReturnsFeatureMap() throws Exception {
        when(capabilitiesService.features()).thenReturn(Map.of("rag", true));
        when(capabilitiesService.release()).thenReturn(Map.of("version", "0.1.0"));
        when(capabilitiesService.limits()).thenReturn(Map.of("maxPageSize", 100));
        when(capabilitiesService.auth()).thenReturn(Map.of("passwordLogin", true));
        when(capabilitiesService.links()).thenReturn(Map.of("documentation", "/docs"));
        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features.rag").value(true))
                .andExpect(jsonPath("$.data.limits.maxPageSize").value(100))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void dictionariesReturnsNamedAnd404ForUnknown() throws Exception {
        mockMvc.perform(get("/api/v1/dictionaries/alarm-severities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].value").exists())
                .andExpect(jsonPath("$.data[0].label").exists());
        mockMvc.perform(get("/api/v1/dictionaries/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void loginRejectsMissingBodyWithValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.fieldErrors").isArray());
    }

    @Test
    void sessionReturns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void alarmsReturns503WhenReadModelUnavailable() throws Exception {
        when(alarmQueryService.isAvailable()).thenReturn(false);
        mockMvc.perform(get("/api/v1/alarms"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void alarmsListReturnsPagedEnvelopeWhenAuthenticated() throws Exception {
        when(alarmQueryService.isAvailable()).thenReturn(true);
        AlarmQueryService.AlarmListItem item = new AlarmQueryService.AlarmListItem(
                "alm_1",
                "fp_1",
                "NodeCpuHigh",
                "P2",
                "FIRING",
                new AlarmQueryService.AlarmResource("NODE", "worker-01", "prod", null, null),
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:10:00Z"),
                3,
                new AlarmQueryService.AlarmAcknowledgement(false, null, null),
                null,
                1);
        when(alarmQueryService.list(any())).thenReturn(new AlarmQueryService.AlarmListResult(List.of(item), 1, 20, 1));
        mockMvc.perform(get("/api/v1/alarms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("alm_1"))
                .andExpect(jsonPath("$.data[0].severity").value("P2"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void alarmDetailReturns404ForUnknownId() throws Exception {
        when(alarmQueryService.isAvailable()).thenReturn(true);
        when(alarmQueryService.detail("alm_missing")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/alarms/alm_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void alarmTimelineReturnsItems() throws Exception {
        when(alarmQueryService.isAvailable()).thenReturn(true);
        when(alarmQueryService.timeline(any(), anyInt(), any()))
                .thenReturn(List.of(new AlarmTimelineItem(
                        "evt_1",
                        "ALARM_FIRING",
                        Instant.parse("2026-07-20T01:00:00Z"),
                        new AlarmTimelineItem.Actor("SYSTEM", null, null),
                        "firing",
                        Map.of(),
                        "req_1")));
        mockMvc.perform(get("/api/v1/alarms/alm_1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("ALARM_FIRING"))
                .andExpect(jsonPath("$.data[0].requestId").value("req_1"));
    }

    @SuppressWarnings("unchecked")
    private static AuthService newAuthService() {
        ObjectProvider<com.kubeoncall.identity.IdentityRepository> repoProvider = mock(ObjectProvider.class);
        when(repoProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<com.kubeoncall.identity.SessionStore> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(null);
        return new AuthService(
                repoProvider, sessionProvider, new PasswordHasher(), new CsrfService(), new KubeOnCallProperties());
    }
}
