package com.kubeoncall.service;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.audit.ExecutionStats;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.PauseMetadata;
import com.kubeoncall.service.audit.ExecutionAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class ExecutionAuditService {

    private static final int RECENT_LIMIT = 20;

    private final ExecutionAuditRepository repository;

    public ExecutionAuditService(ExecutionAuditRepository repository) {
        this.repository = repository;
    }

    public void recordGraphExecution(ExecutionRequestType requestType, GraphState state, Instant startedAt) {
        if (state == null) {
            return;
        }
        ToolOutcome toolOutcome = extractToolOutcome(state);
        record(new ExecutionAuditRecord(
                state.getExecutionId(),
                requestType,
                state.getStatus().name(),
                state.getPauseMetadata() != null || state.getFinalApprovalDecision() != null,
                isAutoHandled(state),
                durationMs(startedAt, state.getUpdatedAt()),
                buildSummary(state),
                buildFailureReason(state),
                extractTools(state),
                Instant.now(),
                readRetryCount(state),
                state.getStatus() == GraphStatus.REPLAN_REQUIRED,
                state.getStatus() == GraphStatus.FAILED || state.getStatus() == GraphStatus.REPLAN_REQUIRED,
                readApprovalLatencyMs(state),
                toolOutcome.successCount(),
                toolOutcome.failureCount()
        ));
    }

    public void recordAlarmExecution(String executionId,
                                     String status,
                                     boolean autoHandled,
                                     boolean approvalRequired,
                                     String summary,
                                     String failureReason,
                                     List<String> tools,
                                     Instant startedAt) {
        List<String> safeTools = tools == null ? List.of() : List.copyOf(tools);
        long toolFailureCount = "DEGRADED".equalsIgnoreCase(status) ? 1 : 0;
        long toolSuccessCount = Math.max(0, safeTools.size() - toolFailureCount);
        record(new ExecutionAuditRecord(
                executionId,
                ExecutionRequestType.ALARM,
                status,
                approvalRequired,
                autoHandled,
                durationMs(startedAt, Instant.now()),
                summary,
                failureReason,
                safeTools,
                Instant.now(),
                0,
                "REPLAN_REQUIRED".equalsIgnoreCase(status),
                "DEGRADED".equalsIgnoreCase(status),
                0,
                toolSuccessCount,
                toolFailureCount
        ));
    }

    public ExecutionStats stats() {
        List<ExecutionAuditRecord> records = repository.findAll();
        long total = records.size();
        long ask = records.stream().filter(record -> record.requestType() == ExecutionRequestType.ASK).count();
        long alarm = records.stream().filter(record -> record.requestType() == ExecutionRequestType.ALARM).count();
        long approvalResume = records.stream().filter(record -> record.requestType() == ExecutionRequestType.APPROVAL_RESUME).count();
        long approvalRequired = records.stream().filter(ExecutionAuditRecord::approvalRequired).count();
        long autoHandled = records.stream().filter(ExecutionAuditRecord::autoHandled).count();
        long success = records.stream().filter(record -> "SUCCESS".equals(record.status()) || "DEDUP_HIT".equals(record.status())).count();
        long failed = records.stream().filter(record -> "FAILED".equals(record.status()) || "REPLAN_REQUIRED".equals(record.status())).count();
        long paused = records.stream().filter(record -> GraphStatus.PAUSED.name().equals(record.status())).count();
        long rejected = records.stream().filter(record -> GraphStatus.REJECTED.name().equals(record.status())).count();
        long replanRequired = records.stream().filter(ExecutionAuditRecord::replanRequired).count();

        long autoEnrichmentTriggered = records.stream().filter(record -> record.retryCount() > 0).count();
        long retryConverged = records.stream()
                .filter(record -> record.retryCount() > 0)
                .filter(record -> "SUCCESS".equals(record.status()))
                .count();

        long toolSuccessTotal = records.stream().mapToLong(ExecutionAuditRecord::toolSuccessCount).sum();
        long toolFailureTotal = records.stream().mapToLong(ExecutionAuditRecord::toolFailureCount).sum();
        long approvalLatencySum = records.stream()
                .filter(record -> record.approvalLatencyMs() > 0)
                .mapToLong(ExecutionAuditRecord::approvalLatencyMs)
                .sum();
        long approvalLatencyCount = records.stream().filter(record -> record.approvalLatencyMs() > 0).count();
        long degradedCount = records.stream().filter(ExecutionAuditRecord::degraded).count();

        List<ExecutionAuditRecord> recent = repository.findRecent(RECENT_LIMIT);
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
                recent
        );
    }

    private void record(ExecutionAuditRecord record) {
        repository.save(record);
    }

    private long durationMs(Instant startedAt, Instant endedAt) {
        Instant start = startedAt == null ? Instant.now() : startedAt;
        Instant end = endedAt == null ? Instant.now() : endedAt;
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private boolean isAutoHandled(GraphState state) {
        return state.getStatus() == GraphStatus.SUCCESS && state.getFinalApprovalDecision() == null;
    }

    private String buildSummary(GraphState state) {
        if (!state.getNodeResults().isEmpty()) {
            NodeResult latest = state.getNodeResults().get(state.getNodeResults().size() - 1);
            return latest.message();
        }
        return state.getUserRequest();
    }

    private String buildFailureReason(GraphState state) {
        if (state.getStatus() == GraphStatus.FAILED || state.getStatus() == GraphStatus.REPLAN_REQUIRED || state.getStatus() == GraphStatus.REJECTED) {
            if (!state.getNodeResults().isEmpty()) {
                return state.getNodeResults().get(state.getNodeResults().size() - 1).message();
            }
        }
        return null;
    }

    private List<String> extractTools(GraphState state) {
        LinkedHashSet<String> tools = new LinkedHashSet<>();
        Object plannerTools = state.getContext().get("plannerAvailableTools");
        if (plannerTools instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Map<?, ?> map && map.get("name") != null) {
                    tools.add(String.valueOf(map.get("name")));
                } else {
                    tools.add(String.valueOf(value));
                }
            }
        }
        Object verifierTool = state.getContext().get("verifierTool");
        if (verifierTool != null) {
            tools.add(String.valueOf(verifierTool));
        }
        Object executorPayload = state.getContext().get("executorPayload");
        if (executorPayload instanceof Map<?, ?> map && map.get("toolName") != null) {
            tools.add(String.valueOf(map.get("toolName")));
        }
        return new ArrayList<>(tools);
    }

    private int readRetryCount(GraphState state) {
        Object retryTrace = state.getContext().get("retryTrace");
        if (retryTrace instanceof List<?> list) {
            return list.size();
        }
        return 0;
    }

    private long readApprovalLatencyMs(GraphState state) {
        Instant requestedAt = state.getApprovalRequestedAt();
        if (requestedAt == null) {
            return 0;
        }
        Instant decidedAt = state.getApprovalDecidedAt();
        if (decidedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(requestedAt, decidedAt).toMillis());
    }

    private ToolOutcome extractToolOutcome(GraphState state) {
        Object executorResult = state.getContext().get("executorResult");
        if (executorResult instanceof Map<?, ?> map) {
            Object statusValue = map.get("status");
            String status = statusValue == null ? "unknown" : String.valueOf(statusValue);
            if ("success".equalsIgnoreCase(status)) {
                return new ToolOutcome(1, 0);
            }
            return new ToolOutcome(0, 1);
        }
        return new ToolOutcome(0, 0);
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0;
        }
        return (double) numerator / denominator;
    }

    private record ToolOutcome(long successCount, long failureCount) {
    }
}
