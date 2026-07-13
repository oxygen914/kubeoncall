package com.kubeoncall.web.dto;

import java.time.Instant;
import java.util.List;

import com.kubeoncall.domain.approval.ApprovalRequest;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.PauseMetadata;

public record ApprovalDetailResponse(
        String executionId,
        String decision,
        boolean processed,
        String taskId,
        String requestedBy,
        String decidedBy,
        String comment,
        Instant requestedAt,
        Instant decidedAt,
        String graphStatus,
        PauseMetadata pauseMetadata,
        List<String> riskReasons,
        List<String> approvalAuditTrail,
        List<String> observations,
        int resumeAttempts,
        String finalApprovalDecision) {

    public static ApprovalDetailResponse from(ApprovalRequest request, GraphState state) {
        return new ApprovalDetailResponse(
                request.executionId(),
                request.decision().name(),
                request.processed(),
                request.taskId(),
                request.requestedBy(),
                request.decidedBy(),
                request.comment(),
                request.requestedAt(),
                request.decidedAt(),
                state.getStatus().name(),
                state.getPauseMetadata(),
                request.riskReasons(),
                state.getApprovalAuditTrail(),
                state.getObservations(),
                state.getResumeAttempts(),
                state.getFinalApprovalDecision() == null
                        ? null
                        : state.getFinalApprovalDecision().name());
    }
}
