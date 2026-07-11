package com.kubeoncall.web.dto;

public record AlarmAcknowledgementRequest(
        String fingerprint,
        String acknowledgedBy,
        String reason,
        Integer ttlSeconds
) {
}
