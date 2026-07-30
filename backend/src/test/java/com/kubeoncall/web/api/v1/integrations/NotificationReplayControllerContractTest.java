package com.kubeoncall.web.api.v1.integrations;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.kubeoncall.notification.application.NotificationReplayService;
import com.kubeoncall.notification.delivery.NotificationDeliveryStatus;
import com.kubeoncall.web.api.v1.RequestIdFilter;
import com.kubeoncall.web.api.v1.V1ApiExceptionHandler;
import com.kubeoncall.web.api.v1.V1Principal;
import com.kubeoncall.web.api.v1.V1Security;

class NotificationReplayControllerContractTest {

    private MockMvc mockMvc;
    private NotificationReplayService replayService;

    @BeforeEach
    void setUp() {
        replayService = mock(NotificationReplayService.class);
        V1Security security = mock(V1Security.class);
        when(security.requirePermission(PermissionCode.INTEGRATION_MANAGE)).thenReturn(principal());
        mockMvc = MockMvcBuilders.standaloneSetup(new NotificationReplayController(provider(replayService), security))
                .addFilters(new RequestIdFilter())
                .setControllerAdvice(new V1ApiExceptionHandler())
                .build();
    }

    @Test
    void replayRequiresPermissionAndReturnsAcceptedEnvelope() throws Exception {
        when(replayService.replay(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new NotificationReplayService.ReplayResult("ndlv_1", NotificationDeliveryStatus.PENDING, 2));

        mockMvc.perform(post("/api/v1/integrations/notifications/ndlv_1/replays")
                        .header("X-Request-Id", "req_notification-replay-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"credential rotated\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.deliveryId").value("ndlv_1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.replayCount").value(2));

        ArgumentCaptor<NotificationReplayService.ReplayCommand> command =
                ArgumentCaptor.forClass(NotificationReplayService.ReplayCommand.class);
        verify(replayService).replay(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorId()).isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(command.getValue().reason()).isEqualTo("credential rotated");
        org.assertj.core.api.Assertions.assertThat(command.getValue().requestId())
                .isEqualTo("req_notification-replay-0001");
    }

    @Test
    void replayRejectsBlankReason() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/notifications/ndlv_1/replays")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
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
