package com.kubeoncall.web.dto;

import java.time.Instant;
import java.util.Map;

public record AlarmMaintenanceWindowRequest(
        Instant startsAt,
        Instant endsAt,
        Map<String, String> matchers,
        String reason,
        String createdBy,
        String approvedBy,
        String approvalReference
) {
}
