package com.kubeoncall.alarm.integration.alertmanager;

import java.time.Instant;
import java.util.Map;

/** Alertmanager's per-alert webhook payload. */
public record AlertmanagerAlertDto(
        String status,
        Map<String, String> labels,
        Map<String, String> annotations,
        Instant startsAt,
        Instant endsAt,
        String generatorURL,
        String fingerprint) {}
