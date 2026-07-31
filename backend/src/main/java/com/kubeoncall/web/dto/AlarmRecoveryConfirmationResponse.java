package com.kubeoncall.web.dto;

import java.time.Instant;

public record AlarmRecoveryConfirmationResponse(
        String fingerprint,
        String status,
        String severity,
        String policyId,
        Instant candidateAt,
        Instant confirmAfter,
        boolean manualConfirmationRequired,
        String confirmedBy,
        boolean healthCheckPassed,
        Instant confirmedAt) {}
