package com.kubeoncall.notification.provider.feishu;

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

class FeishuWebhookNotificationProviderTest {

    private static final long TIMESTAMP = 1_599_360_473L;

    @Test
    void signsAndSendsInteractiveCardThenChecksBusinessCode() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(anyString(), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status", "success", "httpStatus", 200, "response", Map.of("code", 0, "msg", "success")));
        FeishuWebhookNotificationProvider provider =
                new FeishuWebhookNotificationProvider(httpClient, clock(), Map.of("infra", target()));

        provider.send(request());

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(
                        org.mockito.ArgumentMatchers.eq("https://open.feishu.cn/open-apis/bot/v2/hook/example"),
                        body.capture(),
                        org.mockito.ArgumentMatchers.eq(5000),
                        anyMap(),
                        anyMap());
        assertThat(body.getValue().get("msg_type")).isEqualTo("interactive");
        assertThat(body.getValue().get("timestamp")).isEqualTo(String.valueOf(TIMESTAMP));
        assertThat(body.getValue().get("sign")).isEqualTo("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=");
        assertThat(body.getValue().get("card")).isInstanceOf(Map.class);
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
                        Map.of("code", 190020, "msg", "frequency limit")));
        FeishuWebhookNotificationProvider provider =
                new FeishuWebhookNotificationProvider(httpClient, clock(), Map.of("infra", target()));

        assertThatThrownBy(() -> provider.send(request()))
                .isInstanceOfSatisfying(NotificationProviderException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FEISHU_190020");
                    assertThat(exception.retryable()).isTrue();
                });
    }

    @Test
    void rejectsNonPlatformWebhookAtConfigurationTime() {
        NotificationProperties.WebhookTarget target = target();
        target.setWebhookUrl("https://attacker.example/collect");

        assertThatThrownBy(() -> new FeishuWebhookNotificationProvider(
                        mock(ToolHttpClient.class), clock(), Map.of("infra", target)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed platform endpoint");
    }

    @Test
    void signatureMatchesProtocolVector() {
        assertThat(FeishuWebhookNotificationProvider.signature(TIMESTAMP, "test-secret"))
                .isEqualTo("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=");
    }

    private static Clock clock() {
        return Clock.fixed(Instant.ofEpochSecond(TIMESTAMP), ZoneOffset.UTC);
    }

    private static NotificationProperties.WebhookTarget target() {
        NotificationProperties.WebhookTarget target = new NotificationProperties.WebhookTarget();
        target.setWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/example");
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
                Instant.ofEpochSecond(TIMESTAMP));
        NotificationDestination destination = new NotificationDestination(
                "feishu-primary", "feishu", "infra", Set.of(NotificationCapability.GROUP_WEBHOOK), Map.of());
        return new NotificationDeliveryRequest("ndlv_1", message, destination);
    }
}
