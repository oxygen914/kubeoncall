package com.kubeoncall.web.api.v1.alarms;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.alarm.readmodel.AlarmCommandException;
import com.kubeoncall.alarm.readmodel.AlarmCommandService;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.AuthService;
import com.kubeoncall.identity.CsrfService;
import com.kubeoncall.identity.PasswordHasher;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1AuthenticationFilter;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

/**
 * HTTP contract tests for {@code POST /api/v1/alarms/{alarmId}/acknowledgements}: If-Match required,
 * permission enforced, idempotency replay vs reused vs execute, ETag on success, and mapping of
 * {@link AlarmCommandException} to the unified error envelope.
 */
class AlarmCommandsContractTest {

    private MockMvc mockMvc;
    private AlarmCommandService commandService;
    private V1Security security;

    @BeforeEach
    void setUp() {
        commandService = mock(AlarmCommandService.class);
        security = mock(V1Security.class);
        when(security.requirePermission(PermissionCode.ALARM_ACKNOWLEDGE)).thenReturn(authenticatedPrincipal());
        when(security.requirePermission(PermissionCode.ALARM_RECOVER)).thenReturn(authenticatedPrincipal());
        when(security.requirePermission(PermissionCode.ALARM_SILENCE)).thenReturn(authenticatedPrincipal());
        when(commandService.isAvailable()).thenReturn(true);

        AuthService authService = newAuthService();
        V1AuthenticationFilter authFilter = new V1AuthenticationFilter(authService, new KubeOnCallProperties(), true);
        mockMvc = MockMvcBuilders.standaloneSetup(new AlarmCommandsController(commandService, security))
                .addFilters(new RequestIdFilter())
                .addFilter(authFilter)
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void acknowledgeRequiresIfMatchHeader() throws Exception {
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .contentType("application/json")
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void acknowledgeRequiresPermission() throws Exception {
        when(security.requirePermission(PermissionCode.ALARM_ACKNOWLEDGE))
                .thenThrow(new com.kubeoncall.web.api.v1.V1ApiException(
                        403, com.kubeoncall.web.api.v1.V1ApiErrorCode.FORBIDDEN, "no"));
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void acknowledgeSucceedsAndReturnsETag() throws Exception {
        when(commandService.executeAcknowledge(any(), any(), any(), any()))
                .thenReturn(AlarmCommandService.CommandExecution.executed(
                        Map.of("alarmId", "alm_1", "status", "ACKNOWLEDGED", "acknowledged", true, "version", 6),
                        "alm_1",
                        6));
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "5")
                        .header("Idempotency-Key", "0123456789abcdef")
                        .contentType("application/json")
                        .content("{\"reason\":\"expanding\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alarmId").value("alm_1"))
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.version").value(6))
                .andExpect(header().string("ETag", "\"6\""));
    }

    @Test
    void idempotencyReplayReturnsStoredResponse() throws Exception {
        when(commandService.executeAcknowledge(any(), any(), any(), any()))
                .thenReturn(AlarmCommandService.CommandExecution.replayed(
                        Map.of("alarmId", "alm_1", "status", "ACKNOWLEDGED", "version", 6), 200));
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "5")
                        .header("Idempotency-Key", "0123456789abcdef")
                        .contentType("application/json")
                        .content("{\"reason\":\"expanding\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alarmId").value("alm_1"));
    }

    @Test
    void idempotencyReusedReturns409() throws Exception {
        when(commandService.executeAcknowledge(any(), any(), any(), any()))
                .thenReturn(AlarmCommandService.CommandExecution.reused());
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "5")
                        .header("Idempotency-Key", "0123456789abcdef")
                        .contentType("application/json")
                        .content("{\"reason\":\"different\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void versionConflictMapsToEnvelope() throws Exception {
        when(commandService.executeAcknowledge(any(), any(), any(), any()))
                .thenThrow(new AlarmCommandException(
                        AlarmCommandException.Code.RESOURCE_VERSION_CONFLICT, "version changed; current=7"));
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "5")
                        .header("Idempotency-Key", "0123456789abcdef")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("version changed; current=7"));
    }

    @Test
    void serviceUnavailableMapsTo503() throws Exception {
        when(commandService.isAvailable()).thenReturn(false);
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "5")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void acknowledgeRequiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/alarms/alm_1/acknowledgements")
                        .header("If-Match", "5")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void recoveryConfirmationUsesVersionedIdempotentCommand() throws Exception {
        when(commandService.executeRecoveryConfirmation(any(), any(), any(), any()))
                .thenReturn(AlarmCommandService.CommandExecution.executed(
                        Map.of("alarmId", "alm_1", "status", "RESOLVED", "recovered", true, "version", 8), "alm_1", 8));

        mockMvc.perform(post("/api/v1/alarms/alm_1/recovery-confirmations")
                        .header("If-Match", "7")
                        .header("Idempotency-Key", "recovery-key-0001")
                        .contentType("application/json")
                        .content("{\"healthCheckPassed\":true,\"note\":\"stable for ten minutes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.recovered").value(true))
                .andExpect(header().string("ETag", "\"8\""));
    }

    @Test
    void silenceApprovalUsesVersionedIdempotentCommand() throws Exception {
        when(commandService.executeSilenceApproval(any(), any(), any(), any()))
                .thenReturn(AlarmCommandService.CommandExecution.executed(
                        Map.of(
                                "alarmId",
                                "alm_1",
                                "status",
                                "SUPPRESSED",
                                "silenceId",
                                "asil_1",
                                "expiresAt",
                                "2026-07-21T00:00:00Z",
                                "version",
                                9),
                        "alm_1",
                        9));

        mockMvc.perform(post("/api/v1/alarms/alm_1/silence-approvals")
                        .header("If-Match", "8")
                        .header("Idempotency-Key", "silence-key-00001")
                        .contentType("application/json")
                        .content("{\"reason\":\"maintenance\",\"expiresAt\":\"2026-07-21T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.data.silenceId").value("asil_1"))
                .andExpect(header().string("ETag", "\"9\""));
    }

    private static V1Principal authenticatedPrincipal() {
        UserAccount user = new UserAccount(
                1L,
                "usr_1",
                "alice",
                "Alice",
                null,
                "",
                "BCRYPT",
                1L,
                "ACTIVE",
                1L,
                Instant.EPOCH,
                null,
                null,
                0,
                java.util.Set.of("OPERATOR"),
                java.util.Set.of(PermissionCode.ALARM_ACKNOWLEDGE));
        return new V1Principal(
                user, java.util.Set.of(PermissionCode.ALARM_ACKNOWLEDGE), V1Principal.AuthMethod.SESSION);
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
