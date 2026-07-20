package com.kubeoncall.service.audit;

import java.util.List;

import org.springframework.stereotype.Component;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.audit.ExecutionStats;
import com.kubeoncall.domain.graph.GraphStatus;

@Component
public class ExecutionAuditStatistics {

    public ExecutionStats calculate(List<ExecutionAuditRecord> records, List<ExecutionAuditRecord> recent) {
        long total = records.size();
        long ask = countByType(records, ExecutionRequestType.ASK);
        long alarm = countByType(records, ExecutionRequestType.ALARM);
        long approvalResume = countByType(records, ExecutionRequestType.APPROVAL_RESUME);
        long approvalRequired =
                records.stream().filter(ExecutionAuditRecord::approvalRequired).count();
        long autoHandled =
                records.stream().filter(ExecutionAuditRecord::autoHandled).count();
        long success = records.stream()
                .filter(record -> "SUCCESS".equals(record.status()) || "DEDUP_HIT".equals(record.status()))
                .count();
        long failed = records.stream()
                .filter(record -> "FAILED".equals(record.status()) || "REPLAN_REQUIRED".equals(record.status()))
                .count();
        long paused = countByStatus(records, GraphStatus.PAUSED.name());
        long rejected = countByStatus(records, GraphStatus.REJECTED.name());
        long replanRequired =
                records.stream().filter(ExecutionAuditRecord::replanRequired).count();
        long autoEnrichmentTriggered =
                records.stream().filter(record -> record.retryCount() > 0).count();
        long retryConverged = records.stream()
                .filter(record -> record.retryCount() > 0)
                .filter(record -> "SUCCESS".equals(record.status()))
                .count();
        long toolSuccessTotal = records.stream()
                .mapToLong(ExecutionAuditRecord::toolSuccessCount)
                .sum();
        long toolFailureTotal = records.stream()
                .mapToLong(ExecutionAuditRecord::toolFailureCount)
                .sum();
        long approvalLatencySum = records.stream()
                .filter(record -> record.approvalLatencyMs() > 0)
                .mapToLong(ExecutionAuditRecord::approvalLatencyMs)
                .sum();
        long approvalLatencyCount = records.stream()
                .filter(record -> record.approvalLatencyMs() > 0)
                .count();
        long degradedCount =
                records.stream().filter(ExecutionAuditRecord::degraded).count();
        return new ExecutionStats(
                total,
                ask,
                alarm,
                approvalResume,
                approvalRequired,
                autoHandled,
                success,
                failed,
                paused,
                rejected,
                replanRequired,
                ratio(autoEnrichmentTriggered, total),
                ratio(retryConverged, autoEnrichmentTriggered),
                ratio(toolSuccessTotal, toolSuccessTotal + toolFailureTotal),
                ratio(degradedCount, total),
                approvalLatencyCount == 0 ? 0 : approvalLatencySum / approvalLatencyCount,
                recent);
    }

    private long countByType(List<ExecutionAuditRecord> records, ExecutionRequestType requestType) {
        return records.stream()
                .filter(record -> record.requestType() == requestType)
                .count();
    }

    private long countByStatus(List<ExecutionAuditRecord> records, String status) {
        return records.stream().filter(record -> status.equals(record.status())).count();
    }

    private double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }
}
