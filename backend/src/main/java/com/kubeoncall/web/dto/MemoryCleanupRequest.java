package com.kubeoncall.web.dto;

public record MemoryCleanupRequest(Integer scanLimit, Boolean dryRun) {
    public MemoryCleanupRequest(Integer scanLimit) {
        this(scanLimit, false);
    }
}
