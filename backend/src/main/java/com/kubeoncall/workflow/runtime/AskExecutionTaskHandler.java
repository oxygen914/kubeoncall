package com.kubeoncall.workflow.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.service.AskService;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.runtime.WorkflowTaskResultCoordinator.FinalizationResult;

/** Executes a queued Ask request and durably projects its terminal or waiting-for-approval result. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class AskExecutionTaskHandler implements AsyncTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(AskExecutionTaskHandler.class);

    private final AskService askService;
    private final ApprovalService approvalService;
    private final WorkflowTaskResultCoordinator coordinator;

    public AskExecutionTaskHandler(
            AskService askService, ApprovalService approvalService, WorkflowTaskResultCoordinator coordinator) {
        this.askService = askService;
        this.approvalService = approvalService;
        this.coordinator = coordinator;
    }

    @Override
    public String taskType() {
        return "ASK_EXECUTION";
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        WorkflowExecutionRecord execution =
                coordinator.requiredExecution(context.task().resourcePublicId());
        if (coordinator.isDurablyFinished(execution)
                || "WAITING_APPROVAL".equals(execution.status())
                || "WAITING_SANDBOX".equals(execution.status())) {
            if (coordinator.isDurablyFinished(execution)) {
                clearCheckpointBestEffort(execution.publicId());
            }
            return new HandlerResult(existingResult(execution));
        }
        execution = coordinator.markRunning(context);
        if (!"RUNNING".equals(execution.status())) {
            throw new WorkflowCommandException(
                    WorkflowCommandException.Code.CONFLICT,
                    "Ask execution cannot run from status " + execution.status());
        }

        AskService.AskExecutionResult askResult = inspectOrExecute(context, execution.publicId());
        FinalizationResult finalized = coordinator.finalizeResult(context, askResult, "ask");
        if (finalized.terminal()) {
            clearCheckpointBestEffort(finalized.executionId());
        }
        return new HandlerResult(finalized.taskResult());
    }

    private AskService.AskExecutionResult inspectOrExecute(AsyncTaskContext context, String executionId) {
        try {
            return askService.inspectCheckpoint(executionId);
        } catch (IllegalArgumentException notFound) {
            Map<String, Object> request = context.task().request();
            String question = requiredText(request, "question");
            String sessionId = optionalText(request, "sessionId");
            context.requireValidLease();
            Map<String, Object> actor = new LinkedHashMap<>();
            actor.put("userId", requiredLong(request, "actorUserId"));
            actor.put("publicId", requiredText(request, "actorPublicId"));
            actor.put("displayName", optionalText(request, "actorDisplayName"));
            return askService.handleDurably(question, sessionId, executionId, actor);
        }
    }

    private void clearCheckpointBestEffort(String executionId) {
        try {
            approvalService.clearState(executionId);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete terminal graph checkpoint for execution {}", executionId, ex);
        }
    }

    private static Map<String, Object> existingResult(WorkflowExecutionRecord execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.publicId());
        result.put("status", execution.status());
        if (execution.resultSummary() != null) {
            result.put("summary", execution.resultSummary());
        }
        return result;
    }

    private static String requiredText(Map<String, Object> values, String key) {
        String value = optionalText(values, key);
        if (value == null) {
            throw new WorkflowCommandException(WorkflowCommandException.Code.INVALID, "Task request is missing " + key);
        }
        return value;
    }

    private static String optionalText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private static long requiredLong(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed > 0) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Converted below to the stable workflow validation error.
        }
        throw new WorkflowCommandException(WorkflowCommandException.Code.INVALID, "Task request is missing " + key);
    }
}
