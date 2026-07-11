package com.kubeoncall.alarm.maintenance;

import java.time.Instant;
import java.util.Map;

public record AlarmMaintenanceWindow(
        String id,
        Instant startsAt,
        Instant endsAt,
        Map<String, String> matchers,
        String reason,
        String createdBy,
        String approvedBy,
        String approvalReference,
        Instant createdAt
) {
    public AlarmMaintenanceWindow {
        matchers = matchers == null ? Map.of() : Map.copyOf(matchers);
    }

    public boolean activeAt(Instant instant) {
        return instant != null
                && !instant.isBefore(startsAt)
                && instant.isBefore(endsAt);
    }
}
