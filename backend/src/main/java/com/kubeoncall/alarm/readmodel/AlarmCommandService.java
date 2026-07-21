package com.kubeoncall.alarm.readmodel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.readmodel.AlarmCommandException.Code;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.idempotency.IdempotencyService;

/**
 * Transactional command side for alarm acknowledgement, recovery confirmation and silence
 * approval. Every externally exposed command uses {@link #executeAcknowledge},
 * {@link #executeRecoveryConfirmation} or {@link #executeSilenceApproval}; these methods put the
 * idempotency record, incident state, status history, operation audit and outbox event in one MySQL
 * transaction.
 *
 * <p>The lower-level command methods remain public for focused domain/integration tests. HTTP
 * controllers must use the execute methods so a failed command cannot leave a permanent
 * {@code PROCESSING} idempotency record and a committed command cannot lose its replay response.
 */
@Service
public class AlarmCommandService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;
    private final ObjectProvider<AlarmReadRepository> readRepositoryProvider;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public AlarmCommandService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectProvider<AlarmReadRepository> readRepositoryProvider,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.readRepositoryProvider = readRepositoryProvider;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        AlarmReadRepository repository = readRepositoryProvider.getIfAvailable();
        return repository != null && repository.isAvailable();
    }

    @Transactional
    public CommandExecution executeAcknowledge(
            AcknowledgeCommand command,
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest) {
        return executeIdempotent(scope, idempotencyKey, canonicalRequest, () -> {
            AcknowledgeResult result = acknowledge(command);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("alarmId", result.alarmId());
            data.put("status", result.status());
            data.put("acknowledged", true);
            data.put("version", result.version());
            return CommandExecution.executed(data, result.alarmId(), result.version());
        });
    }

    @Transactional
    public CommandExecution executeRecoveryConfirmation(
            RecoveryConfirmationCommand command,
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest) {
        return executeIdempotent(scope, idempotencyKey, canonicalRequest, () -> {
            RecoveryConfirmationResult result = confirmRecovery(command);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("alarmId", result.alarmId());
            data.put("status", result.status());
            data.put("recovered", true);
            data.put("version", result.version());
            return CommandExecution.executed(data, result.alarmId(), result.version());
        });
    }

    @Transactional
    public CommandExecution executeSilenceApproval(
            SilenceApprovalCommand command,
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest) {
        return executeIdempotent(scope, idempotencyKey, canonicalRequest, () -> {
            SilenceApprovalResult result = approveSilence(command);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("alarmId", result.alarmId());
            data.put("status", result.status());
            data.put("silenceId", result.silenceId());
            data.put("expiresAt", result.expiresAt().toString());
            data.put("version", result.version());
            return CommandExecution.executed(data, result.alarmId(), result.version());
        });
    }

    private CommandExecution executeIdempotent(
            IdempotencyService.IdempotencyScope scope,
            String idempotencyKey,
            String canonicalRequest,
            Supplier<CommandExecution> command) {
        IdempotencyService.BeginResult begin = idempotencyService.begin(scope, idempotencyKey, canonicalRequest);
        return switch (begin.action()) {
            case REPLAY ->
                CommandExecution.replayed(
                        parseStoredResponse(begin.responseJson()),
                        begin.httpStatus() == null ? 200 : begin.httpStatus());
            case IN_PROGRESS -> CommandExecution.inProgress();
            case REUSED -> CommandExecution.reused();
            case EXECUTE -> {
                CommandExecution executed = command.get();
                idempotencyService.succeed(
                        scope,
                        idempotencyKey,
                        executed.httpStatus(),
                        executed.data(),
                        "alarm",
                        executed.resourcePublicId());
                yield executed;
            }
        };
    }

    @Transactional
    public AcknowledgeResult acknowledge(AcknowledgeCommand command) {
        JdbcTemplate jdbcTemplate = requiredJdbcTemplate();
        AlarmIncidentRecord before = requiredIncident(jdbcTemplate, command.alarmId());
        requireVersion(before, command.ifMatchVersion());
        if (before.acknowledged() && "ACKNOWLEDGED".equals(before.status())) {
            return AcknowledgeResult.of(before);
        }
        if (!"FIRING".equals(before.status())) {
            throw conflict(command.alarmId(), "FIRING", before.status());
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE koc_alarm_incident
                   SET status = 'ACKNOWLEDGED', acknowledged = TRUE, acknowledged_by = ?,
                       acknowledged_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'FIRING' AND acknowledged = FALSE
                """, command.actorUserId(), ts(command.acknowledgedAt()), before.id(), command.ifMatchVersion());
        requireUpdated(jdbcTemplate, command.alarmId(), updated);

        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_acknowledgement
                  (public_id, incident_id, acknowledged_by, reason, acknowledged_at, expires_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                publicId("aack_"),
                before.id(),
                command.actorUserId(),
                value(command.reason()),
                ts(command.acknowledgedAt()),
                command.expiresAt() == null ? null : ts(command.expiresAt()),
                command.requestId());

        long version = before.version() + 1;
        insertStatusHistory(
                jdbcTemplate,
                before.id(),
                before.status(),
                "ACKNOWLEDGED",
                "ALARM_ACKNOWLEDGED",
                "Acknowledged by " + command.actorDisplayName(),
                command.actorType(),
                command.actorUserId(),
                command.requestId(),
                command.acknowledgedAt());
        Map<String, Object> after = afterSummary("ACKNOWLEDGED", true, version);
        writeAudit(
                command.actor(),
                "alarm.acknowledge",
                command.alarmId(),
                command.reason(),
                before,
                after,
                command.requestId(),
                command.sourceIp(),
                command.userAgent());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "alarm",
                command.alarmId(),
                "alarm.acknowledged",
                Map.of(
                        "alarmId", command.alarmId(),
                        "acknowledgedBy", String.valueOf(command.actorUserId()),
                        "acknowledgedAt", command.acknowledgedAt().toString(),
                        "reason", value(command.reason())),
                command.requestId()));
        return new AcknowledgeResult(command.alarmId(), "ACKNOWLEDGED", after, version);
    }

    @Transactional
    public RecoveryConfirmationResult confirmRecovery(RecoveryConfirmationCommand command) {
        if (!command.healthCheckPassed()) {
            throw new AlarmCommandException(Code.INVALID, "healthCheckPassed must be true");
        }
        JdbcTemplate jdbcTemplate = requiredJdbcTemplate();
        AlarmIncidentRecord before = requiredIncident(jdbcTemplate, command.alarmId());
        requireVersion(before, command.ifMatchVersion());
        if (!"RECOVERY_PENDING".equals(before.status())) {
            throw conflict(command.alarmId(), "RECOVERY_PENDING", before.status());
        }

        int updated = jdbcTemplate.update("""
                UPDATE koc_alarm_incident
                   SET status = 'RESOLVED', resolved_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND status = 'RECOVERY_PENDING'
                """, ts(command.confirmedAt()), before.id(), command.ifMatchVersion());
        requireUpdated(jdbcTemplate, command.alarmId(), updated);

        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_recovery_confirmation
                  (public_id, incident_id, confirmed_by, health_check_passed, note,
                   confirmed_at, request_id)
                VALUES (?, ?, ?, TRUE, ?, ?, ?)
                """,
                publicId("arc_"),
                before.id(),
                command.actorUserId(),
                value(command.note()),
                ts(command.confirmedAt()),
                command.requestId());

        long version = before.version() + 1;
        insertStatusHistory(
                jdbcTemplate,
                before.id(),
                before.status(),
                "RESOLVED",
                "MANUAL_RECOVERY_CONFIRMED",
                value(command.note()),
                command.actorType(),
                command.actorUserId(),
                command.requestId(),
                command.confirmedAt());
        Map<String, Object> after = afterSummary("RESOLVED", before.acknowledged(), version);
        writeAudit(
                command.actor(),
                "alarm.recovery.confirm",
                command.alarmId(),
                command.note(),
                before,
                after,
                command.requestId(),
                command.sourceIp(),
                command.userAgent());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "alarm",
                command.alarmId(),
                "alarm.recovery.confirmed",
                Map.of(
                        "alarmId", command.alarmId(),
                        "confirmedBy", String.valueOf(command.actorUserId()),
                        "confirmedAt", command.confirmedAt().toString()),
                command.requestId()));
        return new RecoveryConfirmationResult(command.alarmId(), "RESOLVED", version);
    }

    @Transactional
    public SilenceApprovalResult approveSilence(SilenceApprovalCommand command) {
        if (command.expiresAt() == null || !command.expiresAt().isAfter(command.approvedAt())) {
            throw new AlarmCommandException(Code.INVALID, "expiresAt must be after approvedAt");
        }
        JdbcTemplate jdbcTemplate = requiredJdbcTemplate();
        AlarmIncidentRecord before = requiredIncident(jdbcTemplate, command.alarmId());
        requireVersion(before, command.ifMatchVersion());
        if (!"FIRING".equals(before.status()) && !"ACKNOWLEDGED".equals(before.status())) {
            throw new AlarmCommandException(
                    Code.CONFLICT,
                    "Alarm must be FIRING or ACKNOWLEDGED for silence approval (current=" + before.status() + ")");
        }

        int updated = jdbcTemplate.update("""
                UPDATE koc_alarm_incident
                   SET status = 'SUPPRESSED', version = version + 1
                 WHERE id = ? AND version = ? AND status IN ('FIRING', 'ACKNOWLEDGED')
                """, before.id(), command.ifMatchVersion());
        requireUpdated(jdbcTemplate, command.alarmId(), updated);

        String silenceId = publicId("asil_");
        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_silence
                  (public_id, incident_id, status, approved_by, reason, approved_at,
                   expires_at, request_id)
                VALUES (?, ?, 'APPROVED', ?, ?, ?, ?, ?)
                """,
                silenceId,
                before.id(),
                command.actorUserId(),
                value(command.reason()),
                ts(command.approvedAt()),
                ts(command.expiresAt()),
                command.requestId());

        long version = before.version() + 1;
        insertStatusHistory(
                jdbcTemplate,
                before.id(),
                before.status(),
                "SUPPRESSED",
                "ALARM_SILENCE_APPROVED",
                value(command.reason()),
                command.actorType(),
                command.actorUserId(),
                command.requestId(),
                command.approvedAt());
        Map<String, Object> after = afterSummary("SUPPRESSED", before.acknowledged(), version);
        after.put("silenceId", silenceId);
        after.put("expiresAt", command.expiresAt().toString());
        writeAudit(
                command.actor(),
                "alarm.silence.approve",
                command.alarmId(),
                command.reason(),
                before,
                after,
                command.requestId(),
                command.sourceIp(),
                command.userAgent());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "alarm",
                command.alarmId(),
                "alarm.silence.approved",
                Map.of(
                        "alarmId", command.alarmId(),
                        "silenceId", silenceId,
                        "approvedBy", String.valueOf(command.actorUserId()),
                        "expiresAt", command.expiresAt().toString(),
                        "reason", value(command.reason())),
                command.requestId()));
        return new SilenceApprovalResult(command.alarmId(), "SUPPRESSED", silenceId, command.expiresAt(), version);
    }

    private JdbcTemplate requiredJdbcTemplate() {
        ensureAvailable();
        JdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            throw new AlarmCommandException(Code.SERVICE_UNAVAILABLE, "MySQL is not available");
        }
        return jdbcTemplate;
    }

    private AlarmIncidentRecord requiredIncident(JdbcTemplate jdbcTemplate, String publicId) {
        return load(jdbcTemplate, publicId)
                .orElseThrow(() -> new AlarmCommandException(Code.NOT_FOUND, "Alarm not found: " + publicId));
    }

    private Optional<AlarmIncidentRecord> load(JdbcTemplate jdbcTemplate, String publicId) {
        AlarmReadRepository repository = readRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return Optional.empty();
        }
        return repository.findByPublicId(publicId);
    }

    private void requireUpdated(JdbcTemplate jdbcTemplate, String alarmId, int updated) {
        if (updated == 1) {
            return;
        }
        AlarmIncidentRecord current = load(jdbcTemplate, alarmId).orElse(null);
        if (current == null) {
            throw new AlarmCommandException(Code.NOT_FOUND, "Alarm not found: " + alarmId);
        }
        throw new AlarmCommandException(
                Code.RESOURCE_VERSION_CONFLICT, "Alarm version changed; current=" + current.version());
    }

    private static void requireVersion(AlarmIncidentRecord incident, long ifMatchVersion) {
        if (incident.version() != ifMatchVersion) {
            throw new AlarmCommandException(
                    Code.RESOURCE_VERSION_CONFLICT, "Alarm version changed; current=" + incident.version());
        }
    }

    private void insertStatusHistory(
            JdbcTemplate jdbcTemplate,
            long incidentId,
            String fromStatus,
            String toStatus,
            String reasonCode,
            String reason,
            String actorType,
            Long actorId,
            String requestId,
            Instant occurredAt) {
        jdbcTemplate.update(
                """
                INSERT INTO koc_alarm_status_history
                  (public_id, incident_id, from_status, to_status, reason_code, reason,
                   actor_type, actor_id, request_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId("ash_"),
                incidentId,
                fromStatus,
                toStatus,
                reasonCode,
                reason,
                actorType,
                actorId,
                requestId,
                ts(occurredAt));
    }

    private void writeAudit(
            Actor actor,
            String action,
            String alarmId,
            String reason,
            AlarmIncidentRecord before,
            Map<String, Object> after,
            String requestId,
            String sourceIp,
            String userAgent) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor(actor.type(), actor.userId(), actor.displayName())
                .action(action)
                .resource("alarm", alarmId)
                .result("SUCCESS")
                .reason(reason)
                .before(beforeSummary(before))
                .after(after)
                .requestId(requestId)
                .sourceIp(sourceIp)
                .userAgent(userAgent)
                .build());
    }

    private Map<String, Object> parseStoredResponse(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(responseJson, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored idempotency response is invalid", ex);
        }
    }

    private static Map<String, Object> afterSummary(String status, boolean acknowledged, long version) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("acknowledged", acknowledged);
        summary.put("version", version);
        return summary;
    }

    private static Map<String, Object> beforeSummary(AlarmIncidentRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", record.status());
        map.put("acknowledged", record.acknowledged());
        map.put("version", record.version());
        return map;
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new AlarmCommandException(Code.SERVICE_UNAVAILABLE, "Alarm command service is not available");
        }
    }

    private static AlarmCommandException conflict(String alarmId, String expected, String current) {
        return new AlarmCommandException(
                Code.CONFLICT, "Alarm " + alarmId + " must be " + expected + " (current=" + current + ")");
    }

    private static String publicId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    public record Actor(Long userId, String type, String displayName) {}

    public record AcknowledgeCommand(
            String alarmId,
            long ifMatchVersion,
            Long actorUserId,
            String actorType,
            String actorDisplayName,
            String reason,
            Instant acknowledgedAt,
            Instant expiresAt,
            String requestId,
            String sourceIp,
            String userAgent) {

        public Actor actor() {
            return new Actor(actorUserId, actorType, actorDisplayName);
        }
    }

    public record RecoveryConfirmationCommand(
            String alarmId,
            long ifMatchVersion,
            Long actorUserId,
            String actorType,
            String actorDisplayName,
            boolean healthCheckPassed,
            String note,
            Instant confirmedAt,
            String requestId,
            String sourceIp,
            String userAgent) {

        public Actor actor() {
            return new Actor(actorUserId, actorType, actorDisplayName);
        }
    }

    public record SilenceApprovalCommand(
            String alarmId,
            long ifMatchVersion,
            Long actorUserId,
            String actorType,
            String actorDisplayName,
            String reason,
            Instant approvedAt,
            Instant expiresAt,
            String requestId,
            String sourceIp,
            String userAgent) {

        public Actor actor() {
            return new Actor(actorUserId, actorType, actorDisplayName);
        }
    }

    public record AcknowledgeResult(String alarmId, String status, Map<String, Object> after, long version) {

        public static AcknowledgeResult of(AlarmIncidentRecord existing) {
            Map<String, Object> map = afterSummary(existing.status(), existing.acknowledged(), existing.version());
            return new AcknowledgeResult(existing.publicId(), existing.status(), map, existing.version());
        }
    }

    public record RecoveryConfirmationResult(String alarmId, String status, long version) {}

    public record SilenceApprovalResult(
            String alarmId, String status, String silenceId, Instant expiresAt, long version) {}

    public record CommandExecution(
            Action action, Map<String, Object> data, int httpStatus, String resourcePublicId, Long version) {

        public enum Action {
            EXECUTED,
            REPLAY,
            IN_PROGRESS,
            REUSED
        }

        public static CommandExecution executed(Map<String, Object> data, String resourcePublicId, long version) {
            return new CommandExecution(Action.EXECUTED, data, 200, resourcePublicId, version);
        }

        public static CommandExecution replayed(Map<String, Object> data, int httpStatus) {
            return new CommandExecution(Action.REPLAY, data, httpStatus, null, versionFrom(data));
        }

        public static CommandExecution inProgress() {
            return new CommandExecution(Action.IN_PROGRESS, Map.of(), 409, null, null);
        }

        public static CommandExecution reused() {
            return new CommandExecution(Action.REUSED, Map.of(), 409, null, null);
        }

        private static Long versionFrom(Map<String, Object> data) {
            Object value = data.get("version");
            return value instanceof Number number ? number.longValue() : null;
        }
    }
}
