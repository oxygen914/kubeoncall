package com.kubeoncall.notification.provider.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.notification.application.NotificationDeliveryRequest;
import com.kubeoncall.notification.config.NotificationProperties;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationDestination;
import com.kubeoncall.notification.domain.NotificationMessage;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.spi.NotificationProviderException;
import com.kubeoncall.tool.http.ToolHttpClient;

class DingTalkWebhookNotificationProviderTest {

    private static final long TIMESTAMP = 1_599_360_473_000L;

    @Test
    void signsUrlAndSendsMarkdownThenChecksBusinessCode() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(anyString(), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status", "success", "httpStatus", 200, "response", Map.of("errcode", 0, "errmsg", "ok")));
        DingTalkWebhookNotificationProvider provider =
                new DingTalkWebhookNotificationProvider(httpClient, clock(), Map.of("infra", target()));

        provider.send(request());

        ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(endpoint.capture(), body.capture(), org.mockito.ArgumentMatchers.eq(5000), anyMap(), anyMap());
        assertThat(endpoint.getValue())
                .startsWith("https://oapi.dingtalk.com/robot/send?access_token=example&timestamp=" + TIMESTAMP)
                .contains("sign=Mee7fHPnIHChjJIuEtxRUaKgA9a%2F2itMq8Jj5AtfVkc%3D");
        assertThat(body.getValue().get("msgtype")).isEqualTo("markdown");
    }

    @Test
    void marksPlatformRateLimitAsRetryableEvenWhenHttpIsSuccessful() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(anyString(), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "httpStatus",
                        200,
                        "response",
                        Map.of("errcode", 130101, "errmsg", "send too frequent")));
        DingTalkWebhookNotificationProvider provider =
                new DingTalkWebhookNotificationProvider(httpClient, clock(), Map.of("infra", target()));

        assertThatThrownBy(() -> provider.send(request()))
                .isInstanceOfSatisfying(NotificationProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("DINGTALK_130101");
                    assertThat(exception.retryable()).isTrue();
                });
    }

    @Test
    void signatureMatchesProtocolVector() {
        assertThat(DingTalkWebhookNotificationProvider.signature(TIMESTAMP, "test-secret"))
                .isEqualTo("Mee7fHPnIHChjJIuEtxRUaKgA9a/2itMq8Jj5AtfVkc=");
    }

    @Test
    void rejectsLookalikeRobotPathAtConfigurationTime() {
        NotificationProperties.WebhookTarget target = target();
        target.setWebhookUrl("https://oapi.dingtalk.com/robot/send-attacker?access_token=example");

        assertThatThrownBy(() -> new DingTalkWebhookNotificationProvider(
                        mock(ToolHttpClient.class), clock(), Map.of("infra", target)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed platform endpoint");
    }

    private static Clock clock() {
        return Clock.fixed(Instant.ofEpochMilli(TIMESTAMP), ZoneOffset.UTC);
    }

    private static NotificationProperties.WebhookTarget target() {
        NotificationProperties.WebhookTarget target = new NotificationProperties.WebhookTarget();
        target.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=example");
        target.setSecret("test-secret");
        return target;
    }

    private static NotificationDeliveryRequest request() {
        NotificationMessage message = new NotificationMessage(
                "event-1",
                "alarm.firing",
                "oncall",
                NotificationPriority.CRITICAL,
                "NodeDown",
                "node is unavailable",
                Map.of("resource", "node-a"),
                List.of(),
                Instant.ofEpochMilli(TIMESTAMP));
        NotificationDestination destination = new NotificationDestination(
                "dingtalk-primary", "dingtalk", "infra", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
        return new NotificationDeliveryRequest("ndlv_1", message, destination);
    }
}
