package com.kubeoncall.memory;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Records metrics and audit evidence for memory operations without affecting their outcome. */
@Service
public class MemoryOperationObserver {

    private static final Logger log = LoggerFactory.getLogger(MemoryOperationObserver.class);

    private final KubeOnCallMetricsService metricsService;
    private final ExecutionAuditService auditService;

    public MemoryOperationObserver(KubeOnCallMetricsService metricsService, ExecutionAuditService auditService) {
        this.metricsService = metricsService;
        this.auditService = auditService;
    }

    public void recordMetric(String operation, String outcome, long count) {
        metricsService.recordMemory(operation, outcome, count);
    }

    public void recordAudit(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        try {
            auditService.recordMemoryOperation(operation, status, summary, startedAt, metadata);
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to record memory operation audit: operation={}, status={}, errorType={}",
                    operation,
                    status,
                    ex.getClass().getSimpleName());
        }
    }

    public void recordCleanup(MemoryService.MemoryCleanupResult result, Instant startedAt) {
        recordAudit(
                "cleanup",
                result.status(),
                "memory cleanup " + result.status(),
                startedAt,
                Map.of(
                        "scanned", result.scanned(),
                        "eligible", result.eligible(),
                        "deleted", result.deleted(),
                        "dryRun", result.dryRun(),
                        "scanLimit", result.scanLimit()));
    }

    public void recordRestore(MemoryService.MemoryRestoreResult result, Instant startedAt) {
        recordMetric("restore", result.status(), "success".equals(result.status()) ? 1 : 0);
        recordAudit(
                "restore",
                result.status(),
                "memory restore " + result.status(),
                startedAt,
                Map.of(
                        "memoryId",
                        result.memoryId(),
                        "previousDeleteReason",
                        result.previousDeleteReason() == null ? "" : result.previousDeleteReason()));
    }
}
