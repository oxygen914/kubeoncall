package com.kubeoncall.web.dto;

public record MemoryConsolidationRequest(
        Integer scanLimit,
        Boolean dryRun
) {
}
