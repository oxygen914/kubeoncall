package com.kubeoncall.web.api.v1.approvals;

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

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Principal.AuthMethod;
import com.kubeoncall.web.api.v1.V1Security;
import com.kubeoncall.workflow.runtime.ApprovalDecisionCommandService;
import com.kubeoncall.workflow.runtime.AsyncCommandResult;

class ApprovalDecisionControllerContractTest {

    private MockMvc mockMvc;
    private ApprovalDecisionCommandService service;
    private V1Security security;

    @BeforeEach
    void setUp() {
        service = mock(ApprovalDecisionCommandService.class);
        security = mock(V1Security.class);
        when(service.isAvailable()).thenReturn(true);
        when(security.requirePermission(PermissionCode.APPROVAL_DECIDE)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new ApprovalDecisionController(provider(service), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void decisionUsesIfMatchIdempotencyAndSessionActor() throws Exception {
        when(service.decide(any(), any(), eq("approval-key-0001"), any()))
                .thenReturn(AsyncCommandResult.executed(
                        Map.of("taskId", "tsk_resume", "status", "PENDING", "executionId", "exe_1"), "tsk_resume"));

        mockMvc.perform(post("/api/v1/approvals/apr_1/decisions")
                        .header("If-Match", "\"3\"")
                        .header("Idempotency-Key", "approval-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"comment\":\"reviewed\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskId").value("tsk_resume"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        ArgumentCaptor<ApprovalDecisionCommandService.DecisionCommand> command =
                ArgumentCaptor.forClass(ApprovalDecisionCommandService.DecisionCommand.class);
        verify(service).decide(command.capture(), any(), eq("approval-key-0001"), any());
        org.assertj.core.api.Assertions.assertThat(command.getValue().approvalId())
                .isEqualTo("apr_1");
        org.assertj.core.api.Assertions.assertThat(command.getValue().ifMatchVersion())
                .isEqualTo(3L);
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorUserId())
                .isEqualTo(7L);
    }

    @Test
    void decisionRequiresIfMatch() throws Exception {
        mockMvc.perform(post("/api/v1/approvals/apr_1/decisions")
                        .header("Idempotency-Key", "approval-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
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
                Set.of(PermissionCode.APPROVAL_DECIDE));
        return new V1Principal(user, user.permissions(), AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
