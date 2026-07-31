package com.kubeoncall.web.api.v1.executions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Principal.AuthMethod;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.workflow.runtime.AsyncCommandResult;
import com.kubeoncall.workflow.runtime.WorkflowSubmissionService;

class ExecutionCommandsControllerContractTest {

    private MockMvc mockMvc;
    private WorkflowSubmissionService service;
    private V1Security security;

    @BeforeEach
    void setUp() {
        service = mock(WorkflowSubmissionService.class);
        security = mock(V1Security.class);
        when(service.isAvailable()).thenReturn(true);
        when(security.requirePermission(PermissionCode.ASK_EXECUTE)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new ExecutionCommandsController(provider(service), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void createUsesSessionActorAndReturnsAcceptedTask() throws Exception {
        when(service.submitAsk(any(), any(), eq("execution-key-0001"), any()))
                .thenReturn(AsyncCommandResult.executed(
                        Map.of("taskId", "tsk_1", "executionId", "exe_1", "status", "PENDING"), "exe_1"));

        mockMvc.perform(post("/api/v1/executions")
                        .header("Idempotency-Key", "execution-key-0001")
                        .header("X-Trace-Id", "trace-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"inspect payment-service","sessionId":"session-1","alarmId":"alm_1"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskId").value("tsk_1"))
                .andExpect(jsonPath("$.data.executionId").value("exe_1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        ArgumentCaptor<WorkflowSubmissionService.SubmitAskCommand> command =
                ArgumentCaptor.forClass(WorkflowSubmissionService.SubmitAskCommand.class);
        verify(service).submitAsk(command.capture(), any(), eq("execution-key-0001"), any());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorUserId())
                .isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorPublicId())
                .isEqualTo("usr_7");
        org.assertj.core.api.Assertions.assertThat(command.getValue().traceId()).isEqualTo("trace-1");
    }

    @Test
    void createRejectsShortIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/executions")
                        .header("Idempotency-Key", "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"inspect\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void durableAskFeatureFlagFailsClosed() throws Exception {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        MockMvc disabled = MockMvcBuilders.standaloneSetup(
                        new ExecutionCommandsController(provider(service), security, properties))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();

        disabled.perform(post("/api/v1/executions")
                        .header("Idempotency-Key", "execution-key-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"inspect\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
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
                Set.of(PermissionCode.ASK_EXECUTE));
        return new V1Principal(user, user.permissions(), AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
