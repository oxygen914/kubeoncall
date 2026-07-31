package com.kubeoncall.alarm.integration.alertmanager;

/** Raised when an Alertmanager webhook cannot be authenticated. */
public class WebhookAuthenticationException extends RuntimeException {

    public WebhookAuthenticationException(String message) {
        super(message);
    }

    public WebhookAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
