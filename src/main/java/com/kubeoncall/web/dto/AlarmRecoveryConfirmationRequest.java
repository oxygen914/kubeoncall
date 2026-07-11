package com.kubeoncall.web.dto;

public record AlarmRecoveryConfirmationRequest(
        String fingerprint,
        String confirmedBy,
        Boolean healthCheckPassed,
        String note
) {
}
