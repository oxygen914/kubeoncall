package com.kubeoncall.notification.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One physical destination in a logical route.
 *
 * <p>{@code target} is a stable configuration alias such as {@code infra-primary}; it must not be a
 * webhook URL or credential.
 */
public record NotificationDestination(
        String id,
        String providerKey,
        String target,
        Set<NotificationCapability> requiredCapabilities,
        Map<String, String> attributes) {

    public NotificationDestination {
        id = requireText(id, "destination id", 128);
        providerKey = requireText(providerKey, "providerKey", 64).toLowerCase(Locale.ROOT);
        target = requireText(target, "target", 128);
        if (target.contains("://")) {
            throw new IllegalArgumentException("target must be a configuration alias, not a URL");
        }
        requiredCapabilities = immutableCapabilities(requiredCapabilities);
        attributes = immutableAttributes(attributes);
    }

    private static Set<NotificationCapability> immutableCapabilities(Set<NotificationCapability> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        for (NotificationCapability capability : source) {
            Objects.requireNonNull(capability, "required capability must not be null");
        }
        return Set.copyOf(source);
    }

    private static Map<String, String> immutableAttributes(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        source.forEach(
                (key, value) -> copied.put(requireText(key, "attribute key"), requireText(value, "attribute value")));
        return Collections.unmodifiableMap(copied);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = requireText(value, field);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
