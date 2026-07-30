package com.kubeoncall.notification.provider.feishu;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.kubeoncall.notification.application.NotificationDeliveryRequest;
import com.kubeoncall.notification.config.NotificationProperties;
import com.kubeoncall.notification.domain.NotificationAction;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.domain.NotificationPriority;
import com.kubeoncall.notification.provider.webhook.ConfiguredWebhookTarget;
import com.kubeoncall.notification.provider.webhook.NotificationMarkdownRenderer;
import com.kubeoncall.notification.provider.webhook.WebhookProviderResponse;
import com.kubeoncall.notification.spi.NotificationProvider;
import com.kubeoncall.notification.spi.NotificationProviderException;
import com.kubeoncall.tool.http.ToolHttpClient;

/** Feishu/Lark custom group bot adapter. It intentionally exposes only one-way webhook capability. */
public final class FeishuWebhookNotificationProvider implements NotificationProvider {

    public static final String PROVIDER_KEY = "feishu";
    private static final Set<String> ALLOWED_HOSTS = Set.of("open.feishu.cn", "open.larksuite.com");

    private final ToolHttpClient httpClient;
    private final Clock clock;
    private final Map<String, ConfiguredWebhookTarget> targets;

    public FeishuWebhookNotificationProvider(
            ToolHttpClient httpClient,
            Clock clock,
            Map<String, NotificationProperties.WebhookTarget> configuredTargets) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.targets = validatedTargets(configuredTargets);
    }

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public Set<NotificationCapability> capabilities() {
        return Set.of(NotificationCapability.GROUP_WEBHOOK);
    }

    @Override
    public SendResult send(NotificationDeliveryRequest request) {
        ConfiguredWebhookTarget target = target(request.destination().target());
        long timestamp = clock.instant().getEpochSecond();
        Map<String, Object> body = render(request, timestamp, target.secret());
        Map<String, Object> result = httpClient.post(
                target.endpoint().toString(),
                body,
                target.timeoutMillis(),
                Map.of("Idempotency-Key", request.deliveryId()),
                Map.of("targetSystem", PROVIDER_KEY, "tool", "notification.send"));
        Map<String, Object> response = WebhookProviderResponse.requireBody(PROVIDER_KEY, result);
        long code = WebhookProviderResponse.code(response, "code", "StatusCode");
        if (code != 0) {
            String message = WebhookProviderResponse.text(response, "msg", "StatusMessage");
            boolean retryable = code == 190020 || WebhookProviderResponse.looksRateLimited(message);
            throw new NotificationProviderException(
                    "FEISHU_" + (code == Long.MIN_VALUE ? "UNKNOWN_ERROR" : code),
                    "Feishu rejected the webhook request: " + WebhookProviderResponse.safe(message),
                    retryable);
        }
        return new SendResult(null, "0", "accepted by Feishu custom bot");
    }

    static String signature(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign Feishu webhook request", exception);
        }
    }

    private Map<String, Object> render(NotificationDeliveryRequest request, long timestamp, String secret) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(
                Map.of("tag", "markdown", "content", NotificationMarkdownRenderer.render(request.message(), false)));
        for (NotificationAction action :
                request.message().actions().stream().limit(3).toList()) {
            elements.add(Map.of(
                    "tag",
                    "button",
                    "text",
                    Map.of("tag", "plain_text", "content", NotificationMarkdownRenderer.safe(action.label())),
                    "type",
                    action.primary() ? "primary" : "default",
                    "behaviors",
                    List.of(Map.of(
                            "type", "open_url", "default_url", action.url().toString()))));
        }
        Map<String, Object> card = Map.of(
                "schema",
                "2.0",
                "header",
                Map.of(
                        "title",
                        Map.of(
                                "tag",
                                "plain_text",
                                "content",
                                NotificationMarkdownRenderer.safe(
                                        request.message().title())),
                        "template",
                        template(request.message().priority())),
                "body",
                Map.of("elements", elements));
        Map<String, Object> body = new LinkedHashMap<>();
        if (secret != null) {
            body.put("timestamp", String.valueOf(timestamp));
            body.put("sign", signature(timestamp, secret));
        }
        body.put("msg_type", "interactive");
        body.put("card", card);
        return body;
    }

    private ConfiguredWebhookTarget target(String alias) {
        ConfiguredWebhookTarget target = targets.get(alias);
        if (target == null) {
            throw new NotificationProviderException(
                    "FEISHU_TARGET_NOT_CONFIGURED", "Feishu target alias is not configured: " + alias, false);
        }
        return target;
    }

    private static Map<String, ConfiguredWebhookTarget> validatedTargets(
            Map<String, NotificationProperties.WebhookTarget> configuredTargets) {
        Map<String, ConfiguredWebhookTarget> validated = new LinkedHashMap<>();
        if (configuredTargets != null) {
            configuredTargets.forEach((alias, configured) -> validated.put(
                    alias,
                    ConfiguredWebhookTarget.from(
                            PROVIDER_KEY, alias, configured, ALLOWED_HOSTS, "/open-apis/bot/v2/hook/")));
        }
        return Map.copyOf(validated);
    }

    private static String template(NotificationPriority priority) {
        return switch (priority) {
            case CRITICAL -> "red";
            case HIGH -> "orange";
            case NORMAL -> "blue";
            case LOW -> "grey";
        };
    }
}
