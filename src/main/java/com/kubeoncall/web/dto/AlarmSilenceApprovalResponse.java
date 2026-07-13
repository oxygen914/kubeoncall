package com.kubeoncall.web.dto;

import java.time.Instant;

public record AlarmSilenceApprovalResponse(
        String fingerprint,
        boolean approved,
        String approvedBy,
        String reason,
        Instant approvedAt,
        Instant expiresAt,
        String approvalKey) {}
