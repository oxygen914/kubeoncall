package com.kubeoncall.notification.provider.dingtalk;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.kubeoncall.notification.application.NotificationDeliveryRequest;
import com.kubeoncall.notification.config.NotificationProperties;
import com.kubeoncall.notification.domain.NotificationCapability;
import com.kubeoncall.notification.provider.webhook.ConfiguredWebhookTarget;
import com.kubeoncall.notification.provider.webhook.NotificationMarkdownRenderer;
import com.kubeoncall.notification.provider.webhook.WebhookProviderResponse;
import com.kubeoncall.notification.spi.NotificationProvider;
import com.kubeoncall.notification.spi.NotificationProviderException;
import com.kubeoncall.tool.http.ToolHttpClient;

/** DingTalk custom group robot adapter with optional timestamp signature. */
public final class DingTalkWebhookNotificationProvider implements NotificationProvider {

    public static final String PROVIDER_KEY = "dingtalk";
    private static final Set<String> ALLOWED_HOSTS = Set.of("oapi.dingtalk.com");

    private final ToolHttpClient httpClient;
    private final Clock clock;
    private final Map<String, ConfiguredWebhookTarget> targets;

    public DingTalkWebhookNotificationProvider(
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
        long timestamp = clock.instant().toEpochMilli();
        String endpoint = signedEndpoint(target, timestamp);
        Map<String, Object> body = Map.of(
                "msgtype",
                "markdown",
                "markdown",
                Map.of(
                        "title",
                        NotificationMarkdownRenderer.safe(request.message().title()),
                        "text",
                        NotificationMarkdownRenderer.render(request.message())),
                "at",
                Map.of("atMobiles", java.util.List.of(), "atUserIds", java.util.List.of(), "isAtAll", false));
        Map<String, Object> result = httpClient.post(
                endpoint,
                body,
                target.timeoutMillis(),
                Map.of("Idempotency-Key", request.deliveryId()),
                Map.of("targetSystem", PROVIDER_KEY, "tool", "notification.send"));
        Map<String, Object> response = WebhookProviderResponse.requireBody(PROVIDER_KEY, result);
        long code = WebhookProviderResponse.code(response, "errcode");
        if (code != 0) {
            String message = WebhookProviderResponse.text(response, "errmsg");
            boolean retryable = code == 130101 || WebhookProviderResponse.looksRateLimited(message);
            throw new NotificationProviderException(
                    "DINGTALK_" + (code == Long.MIN_VALUE ? "UNKNOWN_ERROR" : code),
                    "DingTalk rejected the webhook request: " + WebhookProviderResponse.safe(message),
                    retryable);
        }
        return new SendResult(null, "0", "accepted by DingTalk custom robot");
    }

    static String signature(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign DingTalk webhook request", exception);
        }
    }

    private static String signedEndpoint(ConfiguredWebhookTarget target, long timestamp) {
        if (target.secret() == null) {
            return target.endpoint().toString();
        }
        String separator = target.endpoint().getRawQuery() == null ? "?" : "&";
        return target.endpoint()
                + separator
                + "timestamp="
                + timestamp
                + "&sign="
                + URLEncoder.encode(signature(timestamp, target.secret()), StandardCharsets.UTF_8);
    }

    private ConfiguredWebhookTarget target(String alias) {
        ConfiguredWebhookTarget target = targets.get(alias);
        if (target == null) {
            throw new NotificationProviderException(
                    "DINGTALK_TARGET_NOT_CONFIGURED", "DingTalk target alias is not configured: " + alias, false);
        }
        return target;
    }

    private static Map<String, ConfiguredWebhookTarget> validatedTargets(
            Map<String, NotificationProperties.WebhookTarget> configuredTargets) {
        Map<String, ConfiguredWebhookTarget> validated = new LinkedHashMap<>();
        if (configuredTargets != null) {
            configuredTargets.forEach((alias, configured) -> validated.put(
                    alias,
                    ConfiguredWebhookTarget.from(PROVIDER_KEY, alias, configured, ALLOWED_HOSTS, "/robot/send")));
        }
        return Map.copyOf(validated);
    }
}
