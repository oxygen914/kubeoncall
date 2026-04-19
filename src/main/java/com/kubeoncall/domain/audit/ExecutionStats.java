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
        List<ExecutionAuditRecord> recentExecutions
) {
}
