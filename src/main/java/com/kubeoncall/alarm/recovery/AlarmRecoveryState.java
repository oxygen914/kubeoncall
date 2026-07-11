package com.kubeoncall.alarm.recovery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.kubeoncall.alarm.domain.AlarmSeverity;

import java.time.Instant;

public record AlarmRecoveryState(
        String fingerprint,
        String alarmId,
        AlarmSeverity severity,
        String policyId,
        String recoverExpression,
        Instant candidateAt,
        Instant confirmAfter,
        boolean manualConfirmationRequired,
        String status,
        String confirmedBy,
        boolean healthCheckPassed,
        String note,
        Instant confirmedAt
) {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String TIMED_OUT = "TIMED_OUT";

    @JsonIgnore
    public boolean isPending() {
        return PENDING.equals(status);
    }

    public AlarmRecoveryState confirmed(String actor, boolean healthPassed, String confirmationNote, Instant now) {
        return new AlarmRecoveryState(
                fingerprint, alarmId, severity, policyId, recoverExpression, candidateAt, confirmAfter,
                manualConfirmationRequired, CONFIRMED, actor, healthPassed, confirmationNote, now
        );
    }

    public AlarmRecoveryState cancelled(String cancellationNote, Instant now) {
        return new AlarmRecoveryState(
                fingerprint, alarmId, severity, policyId, recoverExpression, candidateAt, confirmAfter,
                manualConfirmationRequired, CANCELLED, null, false, cancellationNote, now
        );
    }

    public AlarmRecoveryState timedOut(String timeoutNote, Instant now) {
        return new AlarmRecoveryState(
                fingerprint, alarmId, severity, policyId, recoverExpression, candidateAt, confirmAfter,
                manualConfirmationRequired, TIMED_OUT, null, false, timeoutNote, now
        );
    }
}
