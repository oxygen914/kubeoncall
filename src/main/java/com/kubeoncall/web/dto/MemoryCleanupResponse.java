package com.kubeoncall.web.dto;

import java.time.Instant;

public record MemoryCleanupResponse(
        int scanned,
        int deleted,
        String status,
        int scanLimit,
        Instant staleThreshold,
        int staleAfterDays
) {
}
