package com.kubeoncall.workflow;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.execution.WorkflowNodeExecutionRecord;

/** Mirrors each alarm workflow run into the durable execution/node read model. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class AlarmWorkflowFactRecorder {

    private final WorkflowExecutionRepository executionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;

    public AlarmWorkflowFactRecorder(
            WorkflowExecutionRepository executionRepository,
            JdbcTemplate jdbcTemplate,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter) {
        this.executionRepository = executionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public void record(AlarmWorkflowAuditRecorder.AuditRequest request) {
        String executionId = publicId("exe_");
        String incidentPublicId =
                latestIncidentPublicId(request.event().fingerprint()).orElse(null);
        String dedupeKey =
                bounded(request.executionId() + ":" + request.startedAt().toEpochMilli(), 255);
        WorkflowExecutionRecord execution;
        try {
            execution = executionRepository.create(new WorkflowExecutionRepository.CreateExecution(
                    executionId,
                    "ALARM",
                    "ALARM",
                    incidentPublicId,
                    dedupeKey,
                    "RUNNING",
                    riskLevel(request),
                    bounded(request.summary(), 2000),
                    "SYSTEM",
                    null,
                    null,
                    requestId(),
                    null,
                    null,
                    request.startedAt()));
        } catch (DuplicateKeyException duplicate) {
            return;
        }

        Instant finishedAt = Instant.now();
        persistNodes(execution, request, finishedAt);
        String status = durableStatus(request.status());
        if (!executionRepository.updateStatus(
                execution.publicId(),
                execution.version(),
                status,
                bounded(request.summary(), 4000),
                "FAILED".equals(status) ? "ALARM_WORKFLOW_FAILED" : null,
                "FAILED".equals(status) ? bounded(request.failureReason(), 2000) : null,
                request.startedAt(),
                finishedAt)) {
            throw new IllegalStateException("Alarm workflow execution version changed: " + execution.publicId());
        }
        WorkflowExecutionRecord finished =
                executionRepository.findByPublicId(execution.publicId()).orElseThrow();
        if (incidentPublicId != null) {
            jdbcTemplate.update(
                    "UPDATE koc_alarm_incident SET latest_execution_id = ? WHERE public_id = ?",
                    finished.id(),
                    incidentPublicId);
        }
        auditWriter.write(OperationAuditWriter.builder()
                .actor("SYSTEM", null, "Alarm Workflow")
                .action("workflow.alarm.complete")
                .resource("execution", finished.publicId())
                .result("FAILED".equals(status) ? "FAILURE" : "SUCCESS")
                .reason(request.failureReason())
                .after(Map.of(
                        "status", finished.status(),
                        "version", finished.version(),
                        "alarmId", incidentPublicId == null ? "" : incidentPublicId))
                .requestId(finished.requestId())
                .build());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "execution",
                finished.publicId(),
                "execution.updated",
                Map.of(
                        "executionId", finished.publicId(),
                        "type", finished.type(),
                        "status", finished.status(),
                        "version", finished.version()),
                finished.requestId()));
    }

    private void persistNodes(
            WorkflowExecutionRecord execution, AlarmWorkflowAuditRecorder.AuditRequest request, Instant finishedAt) {
        Map<String, Integer> attempts = new HashMap<>();
        for (NodeResult result : request.results()) {
            String name = result.nodeName() == null || result.nodeName().isBlank() ? "unknown" : result.nodeName();
            int attempt = attempts.merge(name, 1, Integer::sum);
            WorkflowNodeExecutionRecord node =
                    executionRepository.createNode(new WorkflowExecutionRepository.CreateNodeExecution(
                            null,
                            execution.publicId(),
                            name,
                            "ALARM_NODE",
                            attempt,
                            "RUNNING",
                            null,
                            request.startedAt()));
            String nodeStatus = result.status() == NodeStatus.SUCCESS ? "SUCCEEDED" : "FAILED";
            String errorCode = result.status() == NodeStatus.SUCCESS
                    ? null
                    : result.status().name();
            if (!executionRepository.updateNode(
                    node.publicId(),
                    node.version(),
                    nodeStatus,
                    bounded(result.message(), 4000),
                    errorCode,
                    errorCode == null ? null : bounded(result.message(), 2000),
                    finishedAt)) {
                throw new IllegalStateException("Alarm workflow node version changed: " + node.publicId());
            }
        }
    }

    private Optional<String> latestIncidentPublicId(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        SELECT public_id
                          FROM koc_alarm_incident
                         WHERE fingerprint = ? AND deleted_at IS NULL
                         ORDER BY cycle_no DESC, id DESC
                         LIMIT 1
                        """, (rs, rowNum) -> rs.getString("public_id"), fingerprint).stream()
                .findFirst();
    }

    private static String durableStatus(String status) {
        String value = status == null ? "" : status.toUpperCase();
        return value.contains("FAIL") || value.contains("ERROR") ? "FAILED" : "SUCCEEDED";
    }

    private static String riskLevel(AlarmWorkflowAuditRecorder.AuditRequest request) {
        if (request.evaluation() == null || request.evaluation().finalSeverity() == null) {
            return "MEDIUM";
        }
        return switch (request.evaluation().finalSeverity()) {
            case P0 -> "CRITICAL";
            case P1 -> "HIGH";
            case P2 -> "MEDIUM";
            case P3, INFO -> "LOW";
        };
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String publicId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String requestId() {
        return "awf_" + UUID.randomUUID().toString().replace("-", "");
    }
}
