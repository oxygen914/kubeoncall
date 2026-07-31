package com.kubeoncall.approval.mysql;

import java.time.Instant;
import java.util.Map;

/** Durable approval request and its single compare-and-set decision. */
public record ApprovalRequestRecord(
        long id,
        String publicId,
        long executionId,
        String executionPublicId,
        String actionType,
        String dedupeKey,
        String riskLevel,
        String status,
        Map<String, Object> context,
        long requestedBy,
        Instant requestedAt,
        Long decidedBy,
        Instant decidedAt,
        String decision,
        String comment,
        Instant expiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
