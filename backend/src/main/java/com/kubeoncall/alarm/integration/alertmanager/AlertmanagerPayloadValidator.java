package com.kubeoncall.alarm.integration.alertmanager;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;

/** Rejects malformed or oversized webhook batches before any durable side effect. */
@Service
public class AlertmanagerPayloadValidator {

    private static final int MAX_LABEL_ENTRIES = 64;
    private static final int MAX_VALUE_LENGTH = 2048;

    private final KubeOnCallProperties properties;

    public AlertmanagerPayloadValidator(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public void validate(AlertmanagerWebhookRequest request) {
        validate(request, -1);
    }

    public void validate(AlertmanagerWebhookRequest request, long contentLength) {
        if (contentLength > properties.getAlarm().getAlertmanagerWebhookMaxPayloadBytes()) {
            throw new WebhookPayloadTooLargeException("Webhook payload is too large");
        }
        if (request == null || request.alerts() == null || request.alerts().isEmpty()) {
            throw new IllegalArgumentException("alerts must not be empty");
        }
        if (request.alerts().size() > properties.getAlarm().getAlertmanagerWebhookMaxAlertsPerRequest()) {
            throw new WebhookPayloadTooLargeException("Too many alerts in webhook request");
        }
        for (AlertmanagerAlertDto alert : request.alerts()) {
            if (alert == null || alert.labels() == null || blank(alert.labels().get("alertname"))) {
                throw new IllegalArgumentException("Every alert must include labels.alertname");
            }
            validateMap(alert.labels(), "labels");
            validateMap(alert.annotations(), "annotations");
        }
    }

    private void validateMap(Map<String, String> values, String field) {
        if (values == null) {
            return;
        }
        if (values.size() > MAX_LABEL_ENTRIES) {
            throw new IllegalArgumentException(field + " contains too many entries");
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (blank(entry.getKey()) || entry.getKey().length() > 128 || valueTooLong(entry.getValue())) {
                throw new IllegalArgumentException(field + " contains an invalid entry");
            }
        }
    }

    private boolean valueTooLong(String value) {
        return value != null && value.length() > MAX_VALUE_LENGTH;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
