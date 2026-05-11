package com.kubeoncall.domain.audit;

import java.util.List;

public record ExecutionStats(
        long totalRequests,
        long askRequests,
        long alarmRequests,
        long approvalResumes,
        long approvalRequiredCount,
        long autoHandledCount,
        long successCount,
        long failedCount,
        long pausedCount,
        long rejectedCount,
        long replanRequiredCount,
        double autoEnrichmentTriggerRate,
        double retryConvergenceRate,
        double toolSuccessRate,
        double degradedRate,
        long averageApprovalLatencyMs,
        List<ExecutionAuditRecord> recentExecutions
) {
}
