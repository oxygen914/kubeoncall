package com.kubeoncall.workflow.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.approval.mysql.ApprovalRequestRecord;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.graph.PauseMetadata;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.service.AskService;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;
import com.kubeoncall.workflow.execution.WorkflowNodeExecutionRecord;

/**
 * Fenced transactional finalizer used by workflow task handlers.
 *
 * <p>It renews the task lease in the same transaction as execution/node/approval facts. A worker
 * whose lease expired cannot commit business state even if its external call returns late.
 */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class WorkflowTaskResultCoordinator {

    private final WorkflowExecutionRepository executionRepository;
    private final MySqlApprovalRepository approvalRepository;
    private final AsyncTaskRepository taskRepository;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final Duration taskLeaseDuration;
    private final Duration approvalTtl;

    public WorkflowTaskResultCoordinator(
            WorkflowExecutionRepository executionRepository,
            MySqlApprovalRepository approvalRepository,
            AsyncTaskRepository taskRepository,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            KubeOnCallProperties properties,
            @Value("${kubeoncall.worker.task.lease-seconds:300}") long taskLeaseSeconds) {
        this.executionRepository = executionRepository;
        this.approvalRepository = approvalRepository;
        this.taskRepository = taskRepository;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.taskLeaseDuration = Duration.ofSeconds(Math.max(1, taskLeaseSeconds));
        this.approvalTtl =
                Duration.ofSeconds(Math.max(1, properties.getApproval().getCallbackTimeoutSeconds()));
    }

    @Transactional
    public WorkflowExecutionRecord markRunning(AsyncTaskContext context) {
        WorkflowExecutionRecord current = requiredExecution(context.task().resourcePublicId());
        if (!"PENDING".equals(current.status())) {
            return current;
        }
        guardLease(context);
        Instant now = Instant.now();
        if (!executionRepository.updateStatus(
                current.publicId(), current.version(), "RUNNING", null, null, null, now, null)) {
            throw versionConflict(current.publicId());
        }
        WorkflowExecutionRecord updated = requiredExecution(current.publicId());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "execution",
                updated.publicId(),
                "execution.updated",
                eventPayload(updated, null),
                context.task().requestId()));
        return updated;
    }

    @Transactional
    public WorkflowExecutionRecord markResuming(AsyncTaskContext context) {
        WorkflowExecutionRecord current = requiredExecution(context.task().resourcePublicId());
        if (!"APPROVED".equals(current.status())) {
            return current;
        }
        guardLease(context);
        Instant now = Instant.now();
        if (!executionRepository.updateStatus(
                current.publicId(), current.version(), "RUNNING", null, null, null, now, null)) {
            throw versionConflict(current.publicId());
        }
        WorkflowExecutionRecord updated = requiredExecution(current.publicId());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "execution",
                updated.publicId(),
                "execution.updated",
                eventPayload(updated, null),
                context.task().requestId()));
        return updated;
    }

    @Transactional
    public FinalizationResult finalizeResult(
            AsyncTaskContext context, AskService.AskExecutionResult result, String phase) {
        WorkflowExecutionRecord current = requiredExecution(context.task().resourcePublicId());
        if (isDurablyFinished(current)
                || "WAITING_APPROVAL".equals(current.status())
                || "WAITING_SANDBOX".equals(current.status())) {
            return existingResult(current);
        }
        guardLease(context);
        Instant now = Instant.now();
        persistNodes(current, result, now);

        String durableStatus = durableStatus(result);
        boolean terminal = isTerminalStatus(durableStatus);
        ApprovalRequestRecord approval = null;
        if ("WAITING_APPROVAL".equals(durableStatus)) {
            approval = ensurePendingApproval(current, context, result, now);
        }
        String errorCode = errorCode(result.status());
        String errorSummary = errorCode == null ? null : bounded(result.message(), 2000);
        boolean updated = executionRepository.updateStatus(
                current.publicId(),
                current.version(),
                durableStatus,
                bounded(result.message(), 4000),
                errorCode,
                errorSummary,
                current.startedAt() == null ? now : null,
                terminal ? now : null);
        if (!updated) {
            throw versionConflict(current.publicId());
        }
        WorkflowExecutionRecord durable = requiredExecution(current.publicId());

        Map<String, Object> before = Map.of("status", current.status(), "version", current.version());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", durable.status());
        after.put("version", durable.version());
        if (approval != null) {
            after.put("approvalId", approval.publicId());
        }
        auditWriter.write(OperationAuditWriter.builder()
                .actor("SYSTEM", null, "Async Task Worker")
                .action("workflow.execution." + ("WAITING_APPROVAL".equals(durableStatus) ? "pause" : "complete"))
                .resource("execution", current.publicId())
                .result(errorCode == null ? "SUCCESS" : "FAILURE")
                .reason(phase)
                .before(before)
                .after(after)
                .requestId(context.task().requestId())
                .build());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "execution",
                durable.publicId(),
                "execution.updated",
                eventPayload(durable, approval),
                context.task().requestId()));
        if (approval != null) {
            outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                    "approval",
                    approval.publicId(),
                    "approval.requested",
                    Map.of(
                            "approvalId", approval.publicId(),
                            "executionId", approval.executionPublicId(),
                            "status", approval.status(),
                            "riskLevel", approval.riskLevel()),
                    context.task().requestId()));
        }
        return new FinalizationResult(
                durable.publicId(),
                durable.status(),
                durable.resultSummary(),
                approval == null ? null : approval.publicId(),
                terminal,
                result.sessionId(),
                result.details());
    }

    public boolean isDurablyFinished(WorkflowExecutionRecord record) {
        return record.finishedAt() != null && isTerminalStatus(record.status());
    }

    public WorkflowExecutionRecord requiredExecution(String executionId) {
        return executionRepository
                .findByPublicId(executionId)
                .orElseThrow(() -> new WorkflowCommandException(
                        WorkflowCommandException.Code.NOT_FOUND, "Execution not found: " + executionId));
    }

    private ApprovalRequestRecord ensurePendingApproval(
            WorkflowExecutionRecord execution,
            AsyncTaskContext context,
            AskService.AskExecutionResult result,
            Instant now) {
        List<ApprovalRequestRecord> existing = approvalRepository
                .list(new MySqlApprovalRepository.ApprovalQuery(
                        1, 20, List.of("PENDING"), List.of(), execution.publicId()))
                .rows();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        PauseMetadata pause = pauseMetadata(result);
        Task task = currentTask(result);
        String actionType = firstNonBlank(
                pause == null ? null : pause.taskType(),
                task == null || task.taskType() == null ? null : task.taskType().name(),
                "WORKFLOW_ACTION");
        String riskLevel = normalizeRisk(firstNonBlank(
                pause == null ? null : pause.riskLevel(),
                task == null || task.riskLevel() == null
                        ? null
                        : task.riskLevel().name(),
                execution.riskLevel()));
        Instant requestedAt = pause == null || pause.pausedAt() == null ? now : pause.pausedAt();
        String taskId =
                firstNonBlank(pause == null ? null : pause.taskId(), task == null ? null : task.taskId(), "workflow");
        String dedupeKey = taskId + ":" + requestedAt.toEpochMilli();
        long requestedBy = execution.actorId() == null ? actorId(context.task().request()) : execution.actorId();

        Map<String, Object> approvalContext = new LinkedHashMap<>();
        approvalContext.put("summary", bounded(result.message(), 2000));
        approvalContext.put("taskId", taskId);
        if (pause != null) {
            putIfPresent(approvalContext, "reason", pause.reason());
            putIfPresent(approvalContext, "target", pause.target());
            approvalContext.put(
                    "riskReasons", pause.riskReasons() == null ? List.of() : List.copyOf(pause.riskReasons()));
            approvalContext.put("snapshot", pause.snapshot() == null ? Map.of() : pause.snapshot());
        }
        return approvalRepository.create(new MySqlApprovalRepository.CreateApproval(
                publicId("apr_"),
                execution.publicId(),
                actionType,
                bounded(dedupeKey, 255),
                riskLevel,
                approvalContext,
                requestedBy,
                requestedAt,
                requestedAt.plus(approvalTtl)));
    }

    private void persistNodes(WorkflowExecutionRecord execution, AskService.AskExecutionResult result, Instant now) {
        List<NodeResult> nodeResults = nodeResults(result);
        if (nodeResults.isEmpty()) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (WorkflowNodeExecutionRecord node : executionRepository.listNodes(execution.publicId())) {
            existing.add(node.nodeName() + "#" + node.attempt());
        }
        Map<String, Integer> attempts = new HashMap<>();
        for (NodeResult node : nodeResults) {
            String nodeName = firstNonBlank(node.nodeName(), "unknown");
            int attempt = attempts.merge(nodeName, 1, Integer::sum);
            if (!existing.add(nodeName + "#" + attempt)) {
                continue;
            }
            WorkflowNodeExecutionRecord created =
                    executionRepository.createNode(new WorkflowExecutionRepository.CreateNodeExecution(
                            null, execution.publicId(), nodeName, "AGENT_NODE", attempt, "RUNNING", null, now));
            String status = nodeStatus(node, isSandboxPause(result));
            String errorCode = node.status() == NodeStatus.FAILURE || node.status() == NodeStatus.RETRY
                    ? firstNonBlank(node.retryReason(), node.status().name())
                    : null;
            if (!executionRepository.updateNode(
                    created.publicId(),
                    created.version(),
                    status,
                    bounded(node.message(), 4000),
                    errorCode,
                    errorCode == null ? null : bounded(node.message(), 2000),
                    now)) {
                throw new IllegalStateException("Workflow node version changed: " + created.publicId());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<NodeResult> nodeResults(AskService.AskExecutionResult result) {
        Object value = result.details().get("nodeResults");
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(NodeResult.class::isInstance)
                    .map(NodeResult.class::cast)
                    .toList();
        }
        return List.of();
    }

    private static Task currentTask(AskService.AskExecutionResult result) {
        Object value = result.details().get("currentTask");
        return value instanceof Task task ? task : null;
    }

    private static PauseMetadata pauseMetadata(AskService.AskExecutionResult result) {
        Object approval = result.details().get("approval");
        if (approval instanceof Map<?, ?> map) {
            Object pause = map.get("pause");
            if (pause instanceof PauseMetadata metadata) {
                return metadata;
            }
        }
        return null;
    }

    private void guardLease(AsyncTaskContext context) {
        context.requireValidLease();
        Instant now = Instant.now();
        boolean renewed = taskRepository.heartbeat(
                context.task().publicId(), context.ownerToken(), context.fencingToken(), now, taskLeaseDuration);
        if (!renewed) {
            throw new IllegalStateException("Async task ownership fence rejected workflow commit");
        }
    }

    private static FinalizationResult existingResult(WorkflowExecutionRecord execution) {
        return new FinalizationResult(
                execution.publicId(),
                execution.status(),
                execution.resultSummary(),
                null,
                execution.finishedAt() != null && isTerminalStatus(execution.status()),
                execution.sessionId(),
                Map.of());
    }

    private static Map<String, Object> eventPayload(WorkflowExecutionRecord execution, ApprovalRequestRecord approval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionId", execution.publicId());
        payload.put("type", execution.type());
        payload.put("status", execution.status());
        payload.put("version", execution.version());
        if (approval != null) {
            payload.put("approvalId", approval.publicId());
        }
        return payload;
    }

    public WorkflowExecutionRecord markSandboxRecovering(AsyncTaskContext context) {
        WorkflowExecutionRecord current = requiredExecution(context.task().resourcePublicId());
        if (!"WAITING_SANDBOX".equals(current.status())) {
            return current;
        }
        guardLease(context);
        Instant now = Instant.now();
        if (!executionRepository.updateStatus(
                current.publicId(), current.version(), "RUNNING", null, null, null, null, null)) {
            throw versionConflict(current.publicId());
        }
        WorkflowExecutionRecord updated = requiredExecution(current.publicId());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "execution",
                updated.publicId(),
                "execution.updated",
                eventPayload(updated, null),
                context.task().requestId()));
        return updated;
    }

    private static String durableStatus(AskService.AskExecutionResult result) {
        if (isSandboxPause(result)) {
            return "WAITING_SANDBOX";
        }
        String graphStatus = result.status();
        GraphStatus status;
        try {
            status = GraphStatus.valueOf(graphStatus);
        } catch (Exception ex) {
            return "FAILED";
        }
        return switch (status) {
            case PAUSED -> "WAITING_APPROVAL";
            case SUCCESS -> "SUCCEEDED";
            case REJECTED -> "REJECTED";
            case RUNNING -> "RUNNING";
            case FAILED, REPLAN_REQUIRED -> "FAILED";
        };
    }

    private static String errorCode(String graphStatus) {
        if (GraphStatus.FAILED.name().equals(graphStatus)) {
            return "WORKFLOW_FAILED";
        }
        if (GraphStatus.REPLAN_REQUIRED.name().equals(graphStatus)) {
            return "REPLAN_REQUIRED";
        }
        return null;
    }

    private static String nodeStatus(NodeResult node, boolean sandboxPause) {
        NodeStatus status = node.status();
        if (status == null) {
            return "FAILED";
        }
        return switch (status) {
            case SUCCESS -> "SUCCEEDED";
            case WAITING ->
                sandboxPause && "executorThinkNode".equals(node.nodeName()) ? "WAITING_SANDBOX" : "WAITING_APPROVAL";
            case FAILURE, RETRY -> "FAILED";
        };
    }

    private static boolean isSandboxPause(AskService.AskExecutionResult result) {
        Object rawNodes = result.details().get("nodeResults");
        if (!(rawNodes instanceof List<?> nodes)) {
            return false;
        }
        return nodes.stream()
                .filter(NodeResult.class::isInstance)
                .map(NodeResult.class::cast)
                .anyMatch(node -> node.status() == NodeStatus.WAITING
                        && "executorThinkNode".equals(node.nodeName())
                        && node.payload().containsKey("runId"));
    }

    private static boolean isTerminalStatus(String status) {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "REJECTED".equals(status)
                || "CANCELLED".equals(status);
    }

    private static long actorId(Map<String, Object> request) {
        Object value = request.get("actorUserId");
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to a stable command error.
        }
        throw new WorkflowCommandException(
                WorkflowCommandException.Code.INVALID, "Task does not contain a user-backed actor");
    }

    private static String normalizeRisk(String value) {
        if (value == null) {
            return "LOW";
        }
        String normalized = value.trim().toUpperCase();
        return Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized) ? normalized : "LOW";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static WorkflowCommandException versionConflict(String executionId) {
        return new WorkflowCommandException(
                WorkflowCommandException.Code.RESOURCE_VERSION_CONFLICT, "Execution version changed: " + executionId);
    }

    public record FinalizationResult(
            String executionId,
            String status,
            String resultSummary,
            String approvalId,
            boolean terminal,
            String sessionId,
            Map<String, Object> details) {

        public FinalizationResult(
                String executionId, String status, String resultSummary, String approvalId, boolean terminal) {
            this(executionId, status, resultSummary, approvalId, terminal, null, Map.of());
        }

        public FinalizationResult {
            details = immutableNonNullCopy(details);
        }

        public Map<String, Object> taskResult() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("executionId", executionId);
            result.put("status", status);
            if (approvalId != null) {
                result.put("approvalId", approvalId);
            }
            if (resultSummary != null) {
                result.put("summary", resultSummary);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                result.put("sessionId", sessionId);
            }
            if (!details.isEmpty()) {
                result.put("details", details);
            }
            return result;
        }
    }

    private static Map<String, Object> immutableNonNullCopy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }
}
