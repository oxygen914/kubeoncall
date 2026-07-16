package com.kubeoncall.alarm.integration.alertmanager;

/** Raised before inbox writes when the webhook payload exceeds the configured intake limit. */
public class WebhookPayloadTooLargeException extends RuntimeException {

    public WebhookPayloadTooLargeException(String message) {
        super(message);
    }
}
