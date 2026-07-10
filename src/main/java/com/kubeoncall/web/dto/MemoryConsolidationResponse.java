package com.kubeoncall.web.dto;

public record MemoryConsolidationResponse(
        int scanned,
        int duplicateGroups,
        int eligible,
        int consolidated,
        String status,
        int scanLimit,
        double similarityThreshold,
        boolean dryRun
) {
}
