package com.kubeoncall.workflow.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.approval.ApprovalService;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.service.AskService;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;
import com.kubeoncall.workflow.execution.WorkflowExecutionRecord;
import com.kubeoncall.workflow.runtime.WorkflowTaskResultCoordinator.FinalizationResult;

/** Applies the durable MySQL approval decision to GraphState and resumes execution asynchronously. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class ApprovalResumeTaskHandler implements AsyncTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalResumeTaskHandler.class);

    private final AskService askService;
    private final ApprovalService approvalService;
    private final WorkflowTaskResultCoordinator coordinator;

    public ApprovalResumeTaskHandler(
            AskService askService, ApprovalService approvalService, WorkflowTaskResultCoordinator coordinator) {
        this.askService = askService;
        this.approvalService = approvalService;
        this.coordinator = coordinator;
    }

    @Override
    public String taskType() {
        return "APPROVAL_RESUME";
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        WorkflowExecutionRecord execution =
                coordinator.requiredExecution(context.task().resourcePublicId());
        if (coordinator.isDurablyFinished(execution) || "WAITING_APPROVAL".equals(execution.status())) {
            if (coordinator.isDurablyFinished(execution)) {
                clearCheckpointBestEffort(execution.publicId());
            }
            return new HandlerResult(existingResult(execution));
        }

        Map<String, Object> request = context.task().request();
        ApprovalDecision decision = requiredDecision(request);
        if (decision == ApprovalDecision.APPROVED) {
            execution = coordinator.markResuming(context);
        }
        if (!"RUNNING".equals(execution.status()) && !"REJECTED".equals(execution.status())) {
            throw new WorkflowCommandException(
                    WorkflowCommandException.Code.CONFLICT,
                    "Approval resume cannot run from status " + execution.status());
        }

        context.requireValidLease();
        applyLegacyDecision(execution.publicId(), decision, request);
        AskService.AskExecutionResult result = askService.resumeAfterApprovalDurably(execution.publicId());
        FinalizationResult finalized = coordinator.finalizeResult(context, result, "approval-resume");
        if (finalized.terminal()) {
            clearCheckpointBestEffort(finalized.executionId());
        }
        return new HandlerResult(finalized.taskResult());
    }

    private void applyLegacyDecision(String executionId, ApprovalDecision decision, Map<String, Object> request) {
        ApprovalRequest current = approvalService.getApproval(executionId);
        if (current.processed()) {
            if (current.decision() != decision) {
                throw new WorkflowCommandException(
                        WorkflowCommandException.Code.CONFLICT,
                        "Graph approval decision conflicts with durable decision");
            }
            return;
        }
        approvalService.decide(
                executionId,
                decision,
                optionalText(request, "comment"),
                firstNonBlank(
                        optionalText(request, "actorDisplayName"), optionalText(request, "actorPublicId"), "unknown"));
    }

    private void clearCheckpointBestEffort(String executionId) {
        try {
            approvalService.clearState(executionId);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete terminal graph checkpoint for execution {}", executionId, ex);
        }
    }

    private static ApprovalDecision requiredDecision(Map<String, Object> request) {
        String value = optionalText(request, "decision");
        try {
            ApprovalDecision decision = ApprovalDecision.valueOf(value == null ? "" : value.toUpperCase());
            if (decision == ApprovalDecision.PENDING) {
                throw new IllegalArgumentException("pending");
            }
            return decision;
        } catch (IllegalArgumentException ex) {
            throw new WorkflowCommandException(
                    WorkflowCommandException.Code.INVALID, "Resume task decision must be APPROVED or REJECTED");
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

    private static String optionalText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
