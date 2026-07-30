package com.kubeoncall.web.api.v1.integrations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kubeoncall.identity.PermissionCode;
import com.kubeoncall.identity.UserAccount;
import com.kubeoncall.notification.application.FeishuRobotConnectionService;
import com.kubeoncall.notification.application.NotificationPublishResult;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class FeishuRobotConnectionControllerContractTest {

    private MockMvc mockMvc;
    private FeishuRobotConnectionService connectionService;

    @BeforeEach
    void setUp() {
        connectionService = mock(FeishuRobotConnectionService.class);
        V1Security security = mock(V1Security.class);
        when(security.requirePermission(PermissionCode.INTEGRATION_MANAGE)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new FeishuRobotConnectionController(provider(connectionService), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void connectsWithRobotIdAndNoRequestBody() throws Exception {
        when(connectionService.connect(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FeishuRobotConnectionService.ConnectionResult(
                        "infra-primary",
                        "feishu-connect-1234",
                        NotificationPublishResult.Status.QUEUED,
                        List.of("ndlv_1")));

        mockMvc.perform(post("/api/v1/integrations/feishu/robots/infra-primary/connections")
                        .header("X-Request-Id", "req-feishu-connect-0001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.robotId").value("infra-primary"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.deliveryIds[0]").value("ndlv_1"));

        ArgumentCaptor<FeishuRobotConnectionService.ConnectionCommand> command =
                ArgumentCaptor.forClass(FeishuRobotConnectionService.ConnectionCommand.class);
        verify(connectionService).connect(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().robotId()).isEqualTo("infra-primary");
        org.assertj.core.api.Assertions.assertThat(command.getValue().idempotencyKey())
                .isNull();
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorId()).isEqualTo(7L);
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
                Instant.EPOCH,
                null,
                null,
                0,
                Set.of("OPERATOR"),
                Set.of(PermissionCode.INTEGRATION_MANAGE));
        return new V1Principal(user, user.permissions(), V1Principal.AuthMethod.SESSION);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
