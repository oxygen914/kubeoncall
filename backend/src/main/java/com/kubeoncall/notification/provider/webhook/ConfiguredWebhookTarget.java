package com.kubeoncall.notification.provider.webhook;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

import com.kubeoncall.notification.config.NotificationProperties;

/** Validated secret-bearing target kept inside the provider adapter boundary. */
public record ConfiguredWebhookTarget(URI endpoint, String secret, int timeoutMillis) {

    public static ConfiguredWebhookTarget from(
            String providerKey,
            String targetAlias,
            NotificationProperties.WebhookTarget configured,
            Set<String> allowedHosts,
            String requiredPathPrefix) {
        if (configured == null) {
            throw new IllegalArgumentException(providerKey + " target is missing: " + targetAlias);
        }
        URI endpoint;
        try {
            endpoint = URI.create(requireText(configured.getWebhookUrl(), providerKey + " webhook URL"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(providerKey + " webhook URL is invalid for target " + targetAlias);
        }
        String host = endpoint.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String path = endpoint.getPath();
        boolean pathAllowed = requiredPathPrefix.endsWith("/")
                ? path != null && path.startsWith(requiredPathPrefix)
                : requiredPathPrefix.equals(path);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || !allowedHosts.contains(normalizedHost)
                || endpoint.getRawUserInfo() != null
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                || endpoint.getRawFragment() != null
                || !endpoint.normalize().equals(endpoint)
                || !pathAllowed) {
            throw new IllegalArgumentException(
                    providerKey + " webhook URL is outside the allowed platform endpoint for target " + targetAlias);
        }
        int timeoutMillis = configured.getTimeoutMillis();
        if (timeoutMillis < 500 || timeoutMillis > 30_000) {
            throw new IllegalArgumentException(providerKey + " timeout must be between 500 and 30000 milliseconds");
        }
        String secret = configured.getSecret() == null || configured.getSecret().isBlank()
                ? null
                : configured.getSecret().trim();
        return new ConfiguredWebhookTarget(endpoint, secret, timeoutMillis);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
