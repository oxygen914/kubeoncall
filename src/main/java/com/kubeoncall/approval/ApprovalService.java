package com.kubeoncall.approval;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.approval.ApprovalDecision;
import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.state.GraphStateStore;

@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;
    private final GraphStateStore graphStateStore;
    private final KubeOnCallProperties properties;

    public ApprovalService(
            ApprovalRepository approvalRepository, GraphStateStore graphStateStore, KubeOnCallProperties properties) {
        this.approvalRepository = approvalRepository;
        this.graphStateStore = graphStateStore;
        this.properties = properties;
    }

    public ApprovalRequest createPending(GraphState state, String requestedBy, String comment) {
        ApprovalRequest existing =
                approvalRepository.findByExecutionId(state.getExecutionId()).orElse(null);
        if (existing != null && !existing.processed()) {
            return existing;
        }

        List<String> riskReasons =
                state.getPauseMetadata() == null || state.getPauseMetadata().riskReasons() == null
                        ? List.of()
                        : List.copyOf(state.getPauseMetadata().riskReasons());
        ApprovalRequest request = new ApprovalRequest(
                state.getExecutionId(),
                state.getTaskPlan(),
                state.getCurrentTask() == null ? null : state.getCurrentTask().taskId(),
                requestedBy,
                ApprovalDecision.PENDING,
                Instant.now(),
                null,
                comment,
                null,
                false,
                riskReasons);
        state.addApprovalAudit("Approval requested by=" + requestedBy + ", taskId=" + request.taskId());
        state.setApprovalRequestedAt(request.requestedAt());
        state.setFinalApprovalDecision(ApprovalDecision.PENDING);
        graphStateStore.save(state, ttl());
        approvalRepository.save(request);
        return request;
    }

    public ApprovalRequest decide(String executionId, ApprovalDecision decision, String comment, String decidedBy) {
        ApprovalRequest existing = approvalRepository
                .findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + executionId));
        if (existing.processed()) {
            throw new IllegalStateException("Approval request already processed: " + executionId);
        }

        GraphState state = graphStateStore
                .find(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Graph state not found: " + executionId));
        if (state.getStatus() != GraphStatus.PAUSED) {
            throw new IllegalStateException("Execution is not awaiting approval: " + executionId);
        }

        ApprovalRequest updated = new ApprovalRequest(
                existing.executionId(),
                existing.taskPlan(),
                existing.taskId(),
                existing.requestedBy(),
                decision,
                existing.requestedAt(),
                Instant.now(),
                comment,
                decidedBy,
                true,
                existing.riskReasons());
        String auditSuffix = (comment == null || comment.isBlank() ? "" : ", comment=" + comment)
                + (decidedBy == null || decidedBy.isBlank() ? "" : ", decidedBy=" + decidedBy);
        state.addObservation("Approval decision=" + decision + auditSuffix);
        state.addApprovalAudit("Approval decision=" + decision + auditSuffix);
        state.setFinalApprovalDecision(decision);
        state.setApprovalDecidedAt(updated.decidedAt());
        if (decision == ApprovalDecision.APPROVED) {
            state.setResumeAttempts(state.getResumeAttempts() + 1);
            state.setStatus(GraphStatus.RUNNING);
        } else {
            state.setStatus(GraphStatus.REJECTED);
            state.setPauseMetadata(null);
        }
        if (!approvalRepository.compareAndSet(existing, updated)) {
            throw new IllegalStateException("Approval request was concurrently processed: " + executionId);
        }
        try {
            graphStateStore.save(state, ttl());
        } catch (RuntimeException ex) {
            approvalRepository.compareAndSet(updated, existing);
            throw ex;
        }
        return updated;
    }

    public ApprovalRequest getApproval(String executionId) {
        return approvalRepository
                .findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Approval request not found: " + executionId));
    }

    public GraphState loadState(String executionId) {
        return graphStateStore
                .find(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Graph state not found: " + executionId));
    }

    public void clearState(String executionId) {
        graphStateStore.delete(executionId);
    }

    public String acquireResumeLease(String executionId) {
        Duration leaseTtl = resumeLeaseTtl();
        return graphStateStore
                .tryAcquireResumeLease(executionId, leaseTtl)
                .orElseThrow(
                        () -> new IllegalStateException("Execution resume is already in progress: " + executionId));
    }

    public void releaseResumeLease(String executionId, String token) {
        graphStateStore.releaseResumeLease(executionId, token);
    }

    public boolean renewResumeLease(String executionId, String token) {
        return graphStateStore.renewResumeLease(executionId, token, resumeLeaseTtl());
    }

    public Duration resumeLeaseTtl() {
        return Duration.ofSeconds(Math.max(1, properties.getApproval().getResumeLeaseSeconds()));
    }

    private Duration ttl() {
        return Duration.ofSeconds(properties.getApproval().getCallbackTimeoutSeconds());
    }
}
