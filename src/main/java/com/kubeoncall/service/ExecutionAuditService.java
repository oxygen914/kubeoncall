package com.kubeoncall.service;

import com.kubeoncall.domain.audit.ExecutionAuditRecord;
import com.kubeoncall.domain.audit.ExecutionRequestType;
import com.kubeoncall.domain.audit.ExecutionStats;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ExecutionAuditService {

    private static final int MAX_RECENT_RECORDS = 100;

    private final List<ExecutionAuditRecord> records = new CopyOnWriteArrayList<>();

    public void recordGraphExecution(ExecutionRequestType requestType, GraphState state, Instant startedAt) {
        if (state == null) {
            return;
        }
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
                Instant.now()
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
        record(new ExecutionAuditRecord(
                executionId,
                ExecutionRequestType.ALARM,
                status,
                approvalRequired,
                autoHandled,
                durationMs(startedAt, Instant.now()),
                summary,
                failureReason,
                tools == null ? List.of() : List.copyOf(tools),
                Instant.now()
        ));
    }

    public ExecutionStats stats() {
        long total = records.size();
        long ask = records.stream().filter(record -> record.requestType() == ExecutionRequestType.ASK).count();
        long alarm = records.stream().filter(record -> record.requestType() == ExecutionRequestType.ALARM).count();
        long approvalResume = records.stream().filter(record -> record.requestType() == ExecutionRequestType.APPROVAL_RESUME).count();
        long approvalRequired = records.stream().filter(ExecutionAuditRecord::approvalRequired).count();
        long autoHandled = records.stream().filter(ExecutionAuditRecord::autoHandled).count();
        long success = records.stream().filter(record -> GraphStatus.SUCCESS.name().equals(record.status())).count();
        long failed = records.stream().filter(record -> GraphStatus.FAILED.name().equals(record.status())).count();
        long paused = records.stream().filter(record -> GraphStatus.PAUSED.name().equals(record.status())).count();
        long rejected = records.stream().filter(record -> GraphStatus.REJECTED.name().equals(record.status())).count();
        List<ExecutionAuditRecord> recent = records.stream()
                .sorted(Comparator.comparing(ExecutionAuditRecord::occurredAt).reversed())
                .limit(20)
                .toList();
        return new ExecutionStats(total, ask, alarm, approvalResume, approvalRequired, autoHandled, success, failed, paused, rejected, recent);
    }

    private void record(ExecutionAuditRecord record) {
        records.add(record);
        if (records.size() > MAX_RECENT_RECORDS) {
            records.remove(0);
        }
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
                tools.add(String.valueOf(value));
            }
        }
        Object verifierTool = state.getContext().get("verifierTool");
        if (verifierTool != null) {
            tools.add(String.valueOf(verifierTool));
        }
        Object executorPayload = state.getContext().get("executorPayload");
        if (executorPayload instanceof java.util.Map<?, ?> map && map.get("toolName") != null) {
            tools.add(String.valueOf(map.get("toolName")));
        }
        return new ArrayList<>(tools);
    }
}
