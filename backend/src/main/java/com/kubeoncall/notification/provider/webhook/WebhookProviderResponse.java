package com.kubeoncall.notification.provider.webhook;

import java.util.Locale;
import java.util.Map;

import com.kubeoncall.notification.spi.NotificationProviderException;
import com.kubeoncall.observability.SensitiveDataRedactor;

/** Normalizes transport and provider business responses without exposing configured endpoints. */
public final class WebhookProviderResponse {

    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private WebhookProviderResponse() {}

    public static Map<String, Object> requireBody(String provider, Map<String, Object> result) {
        int httpStatus = number(result == null ? null : result.get("httpStatus"), 0);
        boolean transportSuccess = result != null
                && "success".equalsIgnoreCase(String.valueOf(result.get("status")))
                && httpStatus >= 200
                && httpStatus < 300;
        if (!transportSuccess) {
            boolean retryable =
                    httpStatus == 0 || httpStatus == 408 || httpStatus == 425 || httpStatus == 429 || httpStatus >= 500;
            String errorType = result == null ? "EMPTY_RESPONSE" : String.valueOf(result.get("errorType"));
            throw new NotificationProviderException(
                    provider.toUpperCase(Locale.ROOT) + "_TRANSPORT_FAILURE",
                    provider + " webhook transport failed: " + safe(errorType),
                    retryable);
        }
        Object response = result.get("response");
        if (!(response instanceof Map<?, ?> responseMap)) {
            throw new NotificationProviderException(
                    provider.toUpperCase(Locale.ROOT) + "_INVALID_RESPONSE",
                    provider + " webhook returned an invalid response",
                    true);
        }
        Map<String, Object> copied = new java.util.LinkedHashMap<>();
        responseMap.forEach((key, value) -> copied.put(String.valueOf(key), value));
        return java.util.Collections.unmodifiableMap(copied);
    }

    public static String text(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    public static long code(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null) {
                return number(value, Long.MIN_VALUE);
            }
        }
        return Long.MIN_VALUE;
    }

    public static boolean looksRateLimited(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("rate")
                || normalized.contains("limit")
                || normalized.contains("frequency")
                || normalized.contains("too many")
                || normalized.contains("频率")
                || normalized.contains("限流");
    }

    public static String safe(String value) {
        String redacted = REDACTOR.redactText(value == null ? "" : value);
        return redacted.length() <= 500 ? redacted : redacted.substring(0, 500);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
