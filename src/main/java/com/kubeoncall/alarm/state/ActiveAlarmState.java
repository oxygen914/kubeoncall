package com.kubeoncall.alarm.state;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.AlarmStatus;

import java.time.Instant;

public record ActiveAlarmState(
        String fingerprint,
        String alarmId,
        String alertName,
        String cluster,
        String namespace,
        String service,
        String resourceName,
        AlarmSeverity severity,
        AlarmStatus status,
        String policyId,
        Instant firstSeen,
        Instant lastSeen,
        long count
) {
}
