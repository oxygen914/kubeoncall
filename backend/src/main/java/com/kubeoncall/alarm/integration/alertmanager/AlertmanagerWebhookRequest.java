package com.kubeoncall.alarm.integration.alertmanager;

import java.util.List;
import java.util.Map;

/** Alertmanager v4-compatible webhook envelope. */
public record AlertmanagerWebhookRequest(
        String version,
        String groupKey,
        Integer truncatedAlerts,
        String status,
        String receiver,
        Map<String, String> groupLabels,
        Map<String, String> commonLabels,
        Map<String, String> commonAnnotations,
        String externalURL,
        List<AlertmanagerAlertDto> alerts) {}
