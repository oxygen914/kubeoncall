package com.kubeoncall.workflow.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.approval.mysql.ApprovalRequestRecord;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository;
import com.kubeoncall.approval.mysql.MySqlApprovalRepository.DecisionOutcome;
import com.kubeoncall.approval.notification.ApprovalNotificationMessageFactory;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.notification.application.NotificationPublisher;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;

/**
 * Atomically records one approval decision and enqueues its resume task.
 *
 * <p>The HTTP request never resumes GraphState inline. If the process stops after commit, a task
 * worker reclaims the durable resume task and finishes the workflow.
 */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class ApprovalDecisionCommandService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<MySqlApprovalRepository> approvalRepositoryProvider;
    private final ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider;
    private final ObjectProvider<AsyncTaskRepository> taskRepositoryProvider;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final NotificationPublisher notificationPublisher;

    public ApprovalDecisionCommandService(
            ObjectProvider<MySqlApprovalRepository> approvalRepositoryProvider,
            ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider,
            ObjectProvider<AsyncTaskRepository> taskRepositoryProvider,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this(
                approvalRepositoryProvider,
                executionRepositoryProvider,
                taskRepositoryProvider,
                auditWriter,
                outboxWriter,
                idempotencyService,
                objectMapper,
                null);
    }

    @Autowired
    public ApprovalDecisionCommandService(
            ObjectProvider<MySqlApprovalRepository> approvalRepositoryProvider,
            ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider,
            ObjectProvider<AsyncTaskRepository> taskRepositoryProvider,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper,
            NotificationPublisher notificationPublisher) {
        this.approvalRepositoryProvider = approvalRepositoryProvider;
        this.executionRepositoryProvider = executionRepositoryProvider;
        this.taskRepositoryProvider = taskRepositoryProvider;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.notificationPublisher = notificationPublisher;
    }

    public boolean isAvailable() {
        MySqlApprovalRepository approvals = approvalRepositoryProvider.getIfAvailable();
        WorkflowExecutionRepository executions = executionRepositoryProvider.getIfAvailable();
        AsyncTaskRepository tasks = taskRepositoryProvider.getIfAvailable();
        return approvals != null
                && executions != null
                && tasks != null
                && approvals.isAvailable()
                && executions.isAvailable()
                && tasks.isAvailable()
                && idempotencyService.isAvailable()
                && auditWriter.isAvailable()
                && outboxWriter.isAvailable();
    }

    @Transactional
    public AsyncCommandResult decide(
            DecisionCommand command,
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest) {
        validate(command);
        ensureAvailable();
        return executeIdempotent(scope, idempotencyKey, canonicalRequest, () -> applyDecision(command));
    }

    private AsyncCommandResult applyDecision(DecisionCommand command) {
        MySqlApprovalRepository approvals = requiredApprovals();
        WorkflowExecutionRepository executions = requiredExecutions();
        AsyncTaskRepository tasks = requiredTasks();
        ApprovalRequestRecord before = approvals
                .findByPublicId(command.approvalId())
                .orElseThrow(() -> notFound("Approval not found: " + command.approvalId()));
        if (before.version() != command.ifMatchVersion()) {
            throw versionConflict("Approval version changed; current=" + before.version());
        }
        if (!"PENDING".equals(before.status())) {
            throw conflict("Approval is not pending: " + before.status());
        }
        WorkflowExecutionRecord execution = executions
                .findByPublicId(before.executionPublicId())
                .orElseThrow(() -> notFound("Execution not found: " + before.executionPublicId()));
        if (!"WAITING_APPROVAL".equals(execution.status())) {
            throw conflict("Execution is not awaiting approval: " + execution.status());
        }

        Instant decidedAt = command.decidedAt() == null ? Instant.now() : command.decidedAt();
        String decision = normalizedDecision(command.decision());
        DecisionOutcome outcome = approvals.decide(
                before.publicId(),
                command.ifMatchVersion(),
                decision,
                command.actorUserId(),
                decidedAt,
                command.comment());
        requireDecision(outcome, before.publicId());

        String executionStatus = "APPROVED".equals(decision) ? "APPROVED" : "REJECTED";
        boolean updated = executions.updateStatus(
                execution.publicId(), execution.version(), executionStatus, null, null, null, null, null);
        if (!updated) {
            throw versionConflict("Execution version changed: " + execution.publicId());
        }

        Map<String, Object> taskRequest = new LinkedHashMap<>();
        taskRequest.put("approvalId", before.publicId());
        taskRequest.put("executionId", before.executionPublicId());
        taskRequest.put("decision", decision);
        taskRequest.put("actorUserId", command.actorUserId());
        taskRequest.put("actorPublicId", command.actorPublicId());
        taskRequest.put("actorDisplayName", command.actorDisplayName());
        if (command.comment() != null && !command.comment().isBlank()) {
            taskRequest.put("comment", command.comment());
        }
        AsyncTaskRecord task = tasks.create(new AsyncTaskRepository.CreateTask(
                null,
                "APPROVAL_RESUME",
                "execution",
                before.executionPublicId(),
                before.publicId(),
                "QUEUED",
                taskRequest,
                5,
                decidedAt,
                safe(command.requestId()),
                blankToNull(command.traceId())));

        Map<String, Object> beforeAudit = new LinkedHashMap<>();
        beforeAudit.put("status", before.status());
        beforeAudit.put("version", before.version());
        Map<String, Object> afterAudit = new LinkedHashMap<>();
        afterAudit.put("status", decision);
        afterAudit.put("version", before.version() + 1);
        afterAudit.put("taskId", task.publicId());
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorUserId(), command.actorDisplayName())
                .action("approval.decide")
                .resource("approval", before.publicId())
                .result("SUCCESS")
                .reason(command.comment())
                .before(beforeAudit)
                .after(afterAudit)
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "approval",
                before.publicId(),
                "approval.decided",
                Map.of(
                        "approvalId", before.publicId(),
                        "executionId", before.executionPublicId(),
                        "decision", decision,
                        "taskId", task.publicId()),
                command.requestId()));
        if (notificationPublisher != null) {
            notificationPublisher.publish(
                    ApprovalNotificationMessageFactory.decided(before, decision, decidedAt), command.requestId());
        }
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "task",
                task.publicId(),
                "task.created",
                Map.of(
                        "taskId", task.publicId(),
                        "taskType", task.taskType(),
                        "resourceId", before.executionPublicId(),
                        "status", task.status()),
                command.requestId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", task.publicId());
        response.put("status", task.status());
        response.put("executionId", before.executionPublicId());
        return AsyncCommandResult.executed(response, task.publicId());
    }

    private AsyncCommandResult executeIdempotent(
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest,
            Supplier<AsyncCommandResult> command) {
        IdempotencyService.BeginResult begin = idempotencyService.begin(scope, idempotencyKey, canonicalRequest);
        return switch (begin.action()) {
            case REPLAY ->
                AsyncCommandResult.replayed(
                        parseStoredResponse(begin.responseJson()),
                        begin.httpStatus() == null ? 202 : begin.httpStatus());
            case IN_PROGRESS -> AsyncCommandResult.inProgress();
            case REUSED -> AsyncCommandResult.reused();
            case EXECUTE -> {
                AsyncCommandResult executed = command.get();
                idempotencyService.succeed(
                        scope,
                        idempotencyKey,
                        executed.httpStatus(),
                        executed.data(),
                        "task",
                        executed.resourcePublicId());
                yield executed;
            }
        };
    }

    private Map<String, Object> parseStoredResponse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored approval response is invalid", ex);
        }
    }

    private void requireDecision(DecisionOutcome outcome, String approvalId) {
        switch (outcome) {
            case DECIDED -> {
                return;
            }
            case NOT_FOUND -> throw notFound("Approval not found: " + approvalId);
            case VERSION_CONFLICT -> throw versionConflict("Approval version changed: " + approvalId);
            case NOT_PENDING -> throw conflict("Approval is no longer pending: " + approvalId);
            case EXPIRED -> throw conflict("Approval has expired: " + approvalId);
        }
    }

    private MySqlApprovalRepository requiredApprovals() {
        MySqlApprovalRepository repository = approvalRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw unavailable();
        }
        return repository;
    }

    private WorkflowExecutionRepository requiredExecutions() {
        WorkflowExecutionRepository repository = executionRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw unavailable();
        }
        return repository;
    }

    private AsyncTaskRepository requiredTasks() {
        AsyncTaskRepository repository = taskRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw unavailable();
        }
        return repository;
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw unavailable();
        }
    }

    private static void validate(DecisionCommand command) {
        if (command == null
                || command.approvalId() == null
                || command.approvalId().isBlank()) {
            throw invalid("approvalId must not be blank");
        }
        normalizedDecision(command.decision());
        if (command.ifMatchVersion() <= 0) {
            throw invalid("If-Match version must be positive");
        }
        if (command.actorUserId() <= 0
                || command.actorPublicId() == null
                || command.actorPublicId().isBlank()) {
            throw invalid("A user-backed actor is required");
        }
    }

    private static String normalizedDecision(String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!"APPROVED".equals(normalized) && !"REJECTED".equals(normalized)) {
            throw invalid("decision must be APPROVED or REJECTED");
        }
        return normalized;
    }

    private static WorkflowCommandException invalid(String message) {
        return new WorkflowCommandException(WorkflowCommandException.Code.INVALID, message);
    }

    private static WorkflowCommandException notFound(String message) {
        return new WorkflowCommandException(WorkflowCommandException.Code.NOT_FOUND, message);
    }

    private static WorkflowCommandException conflict(String message) {
        return new WorkflowCommandException(WorkflowCommandException.Code.CONFLICT, message);
    }

    private static WorkflowCommandException versionConflict(String message) {
        return new WorkflowCommandException(WorkflowCommandException.Code.RESOURCE_VERSION_CONFLICT, message);
    }

    private static WorkflowCommandException unavailable() {
        return new WorkflowCommandException(
                WorkflowCommandException.Code.SERVICE_UNAVAILABLE, "Approval command service is not available");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record DecisionCommand(
            String approvalId,
            long ifMatchVersion,
            String decision,
            String comment,
            long actorUserId,
            String actorPublicId,
            String actorDisplayName,
            Instant decidedAt,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent) {}
}
