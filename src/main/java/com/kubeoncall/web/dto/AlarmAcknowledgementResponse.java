package com.kubeoncall.web.dto;

import java.time.Instant;

public record AlarmAcknowledgementResponse(
        String fingerprint,
        boolean acknowledged,
        String acknowledgedBy,
        String reason,
        Instant acknowledgedAt,
        Instant expiresAt,
        String acknowledgementKey
) {
}
