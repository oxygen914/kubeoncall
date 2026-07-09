package com.kubeoncall.web.dto;

public record AlarmSilenceApprovalRequest(
        String fingerprint,
        String approvedBy,
        String reason,
        Integer ttlSeconds
) {
}
