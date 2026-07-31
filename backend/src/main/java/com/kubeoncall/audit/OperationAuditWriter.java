package com.kubeoncall.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.observability.SensitiveDataRedactor;

/**
 * Appends {@code koc_operation_audit} rows. Designed to be called inside the same Spring transaction
 * as the business update so the audit commit is atomic with the state change (per the consistency
 * ADR). A write failure is deliberately propagated so the surrounding business transaction rolls
 * back. Allowing the state change to commit without its audit trail would violate the command-path
 * invariant and make incident actions impossible to reconstruct.
 */
@Service
public class OperationAuditWriter {

    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectMapper objectMapper;

    public OperationAuditWriter(ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return jdbcTemplateProvider.getIfAvailable() != null;
    }

    public void write(AuditEntry entry) {
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Operation audit is unavailable");
        }
        jdbcTemplate.update(
                """
                    INSERT INTO koc_operation_audit
                      (public_id, actor_type, actor_id, actor_display_name, action, resource_type,
                       resource_public_id, result, reason, before_json, after_json, request_id,
                       trace_id, source_ip, user_agent, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, INET6_ATON(?), ?, ?)
                    """,
                entry.publicId(),
                entry.actorType(),
                entry.actorId(),
                truncate(entry.actorDisplayName(), 255),
                entry.action(),
                entry.resourceType(),
                entry.resourcePublicId(),
                entry.result(),
                truncate(REDACTOR.redactText(entry.reason()), 1000),
                toJson(entry.before()),
                toJson(entry.after()),
                entry.requestId(),
                entry.traceId(),
                entry.sourceIp(),
                truncate(REDACTOR.redactText(entry.userAgent()), 512),
                Timestamp.from(entry.occurredAt()));
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(REDACTOR.redactMap(new LinkedHashMap<>(value)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to serialize operation audit payload", ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Fluent builder for an audit entry so callers cannot forget required fields. */
    public static AuditBuilder builder() {
        return new AuditBuilder();
    }

    public record AuditEntry(
            String publicId,
            String actorType,
            Long actorId,
            String actorDisplayName,
            String action,
            String resourceType,
            String resourcePublicId,
            String result,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent,
            Instant occurredAt) {}

    public static final class AuditBuilder {

        private String actorType = "SYSTEM";
        private Long actorId;
        private String actorDisplayName;
        private String action;
        private String resourceType;
        private String resourcePublicId;
        private String result;
        private String reason;
        private Map<String, Object> before;
        private Map<String, Object> after;
        private String requestId = "";
        private String traceId;
        private String sourceIp;
        private String userAgent;
        private Instant occurredAt = Instant.now();

        public AuditBuilder actor(String type, Long id, String displayName) {
            this.actorType = type;
            this.actorId = id;
            this.actorDisplayName = displayName;
            return this;
        }

        public AuditBuilder action(String action) {
            this.action = action;
            return this;
        }

        public AuditBuilder resource(String type, String publicId) {
            this.resourceType = type;
            this.resourcePublicId = publicId;
            return this;
        }

        public AuditBuilder result(String result) {
            this.result = result;
            return this;
        }

        public AuditBuilder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public AuditBuilder before(Map<String, Object> before) {
            this.before = before;
            return this;
        }

        public AuditBuilder after(Map<String, Object> after) {
            this.after = after;
            return this;
        }

        public AuditBuilder requestId(String requestId) {
            this.requestId = requestId == null ? "" : requestId;
            return this;
        }

        public AuditBuilder sourceIp(String sourceIp) {
            this.sourceIp = sourceIp;
            return this;
        }

        public AuditBuilder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(
                    "oaud_" + UUID.randomUUID().toString().replace("-", ""),
                    actorType,
                    actorId,
                    actorDisplayName,
                    action,
                    resourceType,
                    resourcePublicId,
                    result == null ? "SUCCESS" : result,
                    reason,
                    before,
                    after,
                    requestId,
                    traceId,
                    sourceIp,
                    userAgent,
                    occurredAt);
        }
    }
}
