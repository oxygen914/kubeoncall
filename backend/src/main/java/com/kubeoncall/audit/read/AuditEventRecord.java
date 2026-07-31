package com.kubeoncall.audit.read;

import java.time.Instant;
import java.util.Map;

/**
 * Public-id projection of one operation-audit fact.
 *
 * <p>Database primary keys are deliberately absent. {@code actorPublicId} is the user's public id
 * resolved through a left join and may be {@code null} for system actors or historical users that
 * are no longer resolvable.
 */
public record AuditEventRecord(
        String publicId,
        String actorType,
        String actorPublicId,
        String actorDisplayName,
        String action,
        String resourceType,
        String resourcePublicId,
        String result,
        String reason,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        String requestId,
        String traceId,
        String sourceIp,
        String browser,
        Instant occurredAt) {}
