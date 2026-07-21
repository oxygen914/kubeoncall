package com.kubeoncall.workflow.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.execution.WorkflowExecutionRepository;

/**
 * Creates a durable workflow execution and its first async task in one transaction.
 *
 * <p>The worker receives the preassigned execution id, so MySQL summaries, Redis GraphState,
 * approval facts, operation audit and task results all share one stable correlation id.
 */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class WorkflowSubmissionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider;
    private final ObjectProvider<AsyncTaskRepository> taskRepositoryProvider;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public WorkflowSubmissionService(
            ObjectProvider<WorkflowExecutionRepository> executionRepositoryProvider,
            ObjectProvider<AsyncTaskRepository> taskRepositoryProvider,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.executionRepositoryProvider = executionRepositoryProvider;
        this.taskRepositoryProvider = taskRepositoryProvider;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        WorkflowExecutionRepository executions = executionRepositoryProvider.getIfAvailable();
        AsyncTaskRepository tasks = taskRepositoryProvider.getIfAvailable();
        return executions != null
                && tasks != null
                && executions.isAvailable()
                && tasks.isAvailable()
                && idempotencyService.isAvailable()
                && auditWriter.isAvailable()
                && outboxWriter.isAvailable();
    }

    @Transactional
    public AsyncCommandResult submitAsk(
            SubmitAskCommand command,
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest) {
        validate(command);
        ensureAvailable();
        return executeIdempotent(scope, idempotencyKey, canonicalRequest, () -> create(command, idempotencyKey));
    }

    private AsyncCommandResult create(SubmitAskCommand command, String idempotencyKey) {
        WorkflowExecutionRepository executions = requiredExecutions();
        AsyncTaskRepository tasks = requiredTasks();
        String executionId = publicId("exe_");
        String dedupeKey = bounded(command.actorPublicId() + ":" + idempotencyKey, 255);
        WorkflowExecutionRecord execution = executions.create(new WorkflowExecutionRepository.CreateExecution(
                executionId,
                "ASK",
                command.alarmId() == null || command.alarmId().isBlank() ? "USER" : "ALARM",
                blankToNull(command.alarmId()),
                dedupeKey,
                "PENDING",
                "LOW",
                bounded(command.question(), 2000),
                "USER",
                command.actorUserId(),
                blankToNull(command.sessionId()),
                safe(command.requestId()),
                blankToNull(command.traceId()),
                "graph-state:" + executionId,
                null));

        Map<String, Object> taskRequest = new LinkedHashMap<>();
        taskRequest.put("question", command.question());
        putIfPresent(taskRequest, "sessionId", command.sessionId());
        putIfPresent(taskRequest, "alarmId", command.alarmId());
        taskRequest.put("actorUserId", command.actorUserId());
        taskRequest.put("actorPublicId", command.actorPublicId());
        taskRequest.put("actorDisplayName", command.actorDisplayName());
        AsyncTaskRecord task = tasks.create(new AsyncTaskRepository.CreateTask(
                null,
                "ASK_EXECUTION",
                "execution",
                execution.publicId(),
                dedupeKey,
                "QUEUED",
                taskRequest,
                5,
                Instant.now(),
                safe(command.requestId()),
                blankToNull(command.traceId())));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("executionId", execution.publicId());
        after.put("taskId", task.publicId());
        after.put("status", execution.status());
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorUserId(), command.actorDisplayName())
                .action("workflow.execution.create")
                .resource("execution", execution.publicId())
                .result("SUCCESS")
                .reason(command.question())
                .after(after)
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "execution",
                execution.publicId(),
                "execution.created",
                Map.of(
                        "executionId", execution.publicId(),
                        "taskId", task.publicId(),
                        "type", execution.type(),
                        "status", execution.status()),
                command.requestId()));
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "task",
                task.publicId(),
                "task.created",
                Map.of(
                        "taskId", task.publicId(),
                        "taskType", task.taskType(),
                        "resourceId", execution.publicId(),
                        "status", task.status()),
                command.requestId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", task.publicId());
        response.put("executionId", execution.publicId());
        response.put("status", task.status());
        return AsyncCommandResult.executed(response, execution.publicId());
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
                        "execution",
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
            throw new IllegalStateException("Stored workflow response is invalid", ex);
        }
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

    private static WorkflowCommandException unavailable() {
        return new WorkflowCommandException(
                WorkflowCommandException.Code.SERVICE_UNAVAILABLE, "Workflow command service is not available");
    }

    private static void validate(SubmitAskCommand command) {
        if (command == null || command.question() == null || command.question().isBlank()) {
            throw new WorkflowCommandException(WorkflowCommandException.Code.INVALID, "question must not be blank");
        }
        if (command.actorUserId() <= 0
                || command.actorPublicId() == null
                || command.actorPublicId().isBlank()) {
            throw new WorkflowCommandException(
                    WorkflowCommandException.Code.INVALID, "A user-backed actor is required");
        }
    }

    private static String publicId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    public record SubmitAskCommand(
            String question,
            String sessionId,
            String alarmId,
            long actorUserId,
            String actorPublicId,
            String actorDisplayName,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent) {}
}
