package com.kubeoncall.notification.spi;

/** A normalized provider failure. The message must be safe for logs and delivery records. */
public final class NotificationProviderException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public NotificationProviderException(String code, String message, boolean retryable) {
        super(requireText(message, "provider error message"));
        this.code = requireText(code, "provider error code");
        this.retryable = retryable;
    }

    public NotificationProviderException(String code, String message, boolean retryable, Throwable cause) {
        super(requireText(message, "provider error message"), cause);
        this.code = requireText(code, "provider error code");
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
