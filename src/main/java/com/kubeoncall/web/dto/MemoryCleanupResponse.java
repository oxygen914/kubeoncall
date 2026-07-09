package com.kubeoncall.web.dto;

public record MemoryCleanupResponse(
        int scanned,
        int deleted,
        String status
) {
}
