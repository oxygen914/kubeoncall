package com.kubeoncall.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.audit.ExecutionStats;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.service.audit.ExecutionAuditRepository;
import com.kubeoncall.service.audit.ExecutionAuditStatistics;
import com.kubeoncall.service.audit.GraphAuditMetadataFactory;
import com.kubeoncall.service.audit.OperationAuditRecordFactory;

@Service
public class ExecutionAuditService {

    private final ExecutionAuditRepository repository;
    private final KubeOnCallMetricsService metricsService;
    private final OperationAuditRecordFactory operationAuditRecordFactory;
    private final GraphAuditMetadataFactory graphAuditMetadataFactory;
    private final ExecutionAuditStatistics auditStatistics;

    public ExecutionAuditService(
            ExecutionAuditRepository repository,
            KubeOnCallMetricsService metricsService,
            OperationAuditRecordFactory operationAuditRecordFactory,
            GraphAuditMetadataFactory graphAuditMetadataFactory,
            ExecutionAuditStatistics auditStatistics) {
        this.repository = repository;
        this.metricsService = metricsService;
        this.operationAuditRecordFactory = operationAuditRecordFactory;
        this.graphAuditMetadataFactory = graphAuditMetadataFactory;
        this.auditStatistics = auditStatistics;
    }

    public void recordGraphExecution(ExecutionRequestType requestType, GraphState state, Instant startedAt) {
        if (state == null) {
            return;
        }
        ToolOutcome toolOutcome = extractToolOutcome(state);
        Map<String, Object> metadata = graphAuditMetadataFactory.build(state);
        ExecutionAuditRecord record = new ExecutionAuditRecord(
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
                graphAuditMetadataFactory.retryCount(state),
                state.getStatus() == GraphStatus.REPLAN_REQUIRED,
                isDegraded(state),
                readApprovalLatencyMs(state),
                toolOutcome.successCount(),
                toolOutcome.failureCount(),
                metadata);
        record(record);
        metricsService.recordGraphExecution(
                requestType.name(), record.status(), record.degraded(), record.approvalRequired());
    }

    public void recordAlarmExecution(
            String executionId,
            String status,
            boolean autoHandled,
            boolean approvalRequired,
            String summary,
            String failureReason,
            List<String> tools,
            Instant startedAt) {
        recordAlarmExecution(
                executionId, status, autoHandled, approvalRequired, summary, failureReason, tools, startedAt, Map.of());
    }

    public void recordAlarmExecution(
            String executionId,
            String status,
            boolean autoHandled,
            boolean approvalRequired,
            String summary,
            String failureReason,
            List<String> tools,
            Instant startedAt,
            Map<String, Object> metadata) {
        List<String> safeTools = tools == null ? List.of() : List.copyOf(tools);
        long toolFailureCount = "DEGRADED".equalsIgnoreCase(status) ? 1 : 0;
        long toolSuccessCount = Math.max(0, safeTools.size() - toolFailureCount);
        ExecutionAuditRecord record = new ExecutionAuditRecord(
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
                toolFailureCount,
                metadata == null ? Map.of() : metadata);
        record(record);
        metricsService.recordAlarmExecution(record.status(), record.degraded(), record.autoHandled());
    }

    public void recordMemoryOperation(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        record(operationAuditRecordFactory.memory(operation, status, summary, startedAt, metadata));
    }

    public void recordKnowledgeOperation(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        record(operationAuditRecordFactory.knowledge(operation, status, summary, startedAt, metadata));
    }

    public void recordSkillOperation(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        record(operationAuditRecordFactory.skill(operation, status, summary, startedAt, metadata));
    }

    public void recordChangeEventOperation(
            String operation, String status, String summary, Instant startedAt, Map<String, Object> metadata) {
        record(operationAuditRecordFactory.changeEvent(operation, status, summary, startedAt, metadata));
    }

    public ExecutionStats stats() {
        return auditStatistics.calculate(repository.findAll(), repository.findRecent(20));
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

    private boolean isDegraded(GraphState state) {
        return state.getStatus() == GraphStatus.FAILED
                || state.getStatus() == GraphStatus.REPLAN_REQUIRED
                || Boolean.TRUE.equals(state.getContext().get("plannerDegraded"));
    }

    private String buildSummary(GraphState state) {
        if (!state.getNodeResults().isEmpty()) {
            NodeResult latest =
                    state.getNodeResults().get(state.getNodeResults().size() - 1);
            return latest.message();
        }
        return state.getUserRequest();
    }

    private String buildFailureReason(GraphState state) {
        if (state.getStatus() == GraphStatus.FAILED
                || state.getStatus() == GraphStatus.REPLAN_REQUIRED
                || state.getStatus() == GraphStatus.REJECTED) {
            if (!state.getNodeResults().isEmpty()) {
                return state.getNodeResults()
                        .get(state.getNodeResults().size() - 1)
                        .message();
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

    private record ToolOutcome(long successCount, long failureCount) {}
}
