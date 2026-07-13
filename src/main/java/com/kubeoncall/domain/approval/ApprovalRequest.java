package com.kubeoncall.domain.approval;

import java.time.Instant;
import java.util.List;

import com.kubeoncall.domain.task.TaskPlan;

public record ApprovalRequest(
        String executionId,
        TaskPlan taskPlan,
        String taskId,
        String requestedBy,
        ApprovalDecision decision,
        Instant requestedAt,
        Instant decidedAt,
        String comment,
        String decidedBy,
        boolean processed,
        List<String> riskReasons) {}
