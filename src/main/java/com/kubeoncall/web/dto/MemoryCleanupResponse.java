package com.kubeoncall.web.dto;

import java.time.Instant;

public record MemoryCleanupResponse(
        int scanned,
        int eligible,
        int deleted,
        String status,
        int scanLimit,
        Instant staleThreshold,
        int staleAfterDays,
        boolean dryRun) {}
