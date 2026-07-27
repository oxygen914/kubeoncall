package com.kubeoncall.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.idempotency.IdempotencyService;
import com.kubeoncall.sandbox.domain.SandboxRunMode;
import com.kubeoncall.sandbox.policy.SandboxExecutionPolicy;
import com.kubeoncall.sandbox.policy.SandboxToolCatalog;
import com.kubeoncall.sandbox.policy.SandboxToolSpec;

/**
 * Transactional command boundary for sandbox Run creation and cancellation.
 *
 * <p>This service persists only the durable control-plane fact and emits an outbox event. It never
 * contacts the Sandbox Controller on an HTTP request thread; SBX-12/13 will consume the event and
 * reconcile the run asynchronously.
 */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SandboxRunCommandService {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectProvider<SandboxRunRepository> repositoryProvider;
    private final ObjectProvider<SandboxToolCatalog> toolCatalogProvider;
    private final KubeOnCallProperties properties;
    private final SandboxExecutionPolicy policy;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public SandboxRunCommandService(
            ObjectProvider<SandboxRunRepository> repositoryProvider,
            ObjectProvider<SandboxToolCatalog> toolCatalogProvider,
            KubeOnCallProperties properties,
            SandboxExecutionPolicy policy,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.repositoryProvider = repositoryProvider;
        this.toolCatalogProvider = toolCatalogProvider;
        this.properties = properties;
        this.policy = policy;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        SandboxRunRepository repository = repositoryProvider.getIfAvailable();
        return repository != null
                && repository.isAvailable()
                && auditWriter.isAvailable()
                && outboxWriter.isAvailable()
                && idempotencyService.isAvailable();
    }

    @Transactional
    public CommandResult create(CreateCommand command, IdempotencyService.IdempotencyScope scope, String key) {
        requireAvailable();
        validate(command);
        IdempotencyService.BeginResult begin = idempotencyService.begin(scope, key, canonical(command));
        switch (begin.action()) {
            case REPLAY:
                return CommandResult.replay(parseResponse(begin.responseJson()), begin.httpStatus());
            case IN_PROGRESS:
                throw new SandboxRunCommandException(
                        SandboxRunCommandException.Code.IDEMPOTENCY_IN_PROGRESS,
                        "An idempotent sandbox request for this key is already in progress");
            case REUSED:
                throw new SandboxRunCommandException(
                        SandboxRunCommandException.Code.IDEMPOTENCY_REUSED,
                        "Idempotency-Key was used with a different request");
            case EXECUTE:
                break;
        }

        SandboxToolSpec tool = resolveTool(command);
        SandboxExecutionPolicy.Decision decision = policy.evaluate(
                properties.getSandbox().isModeEnabled(command.mode()),
                tool,
                command.generatedCodeAutoRun(),
                command.approvalWaiverRequested());
        if (!decision.isAllowed()) {
            throw invalid(decision.denialReason());
        }
        SandboxRunRepository repository = requiredRepository();
        SandboxRunRecord run = repository.create(new SandboxRunRepository.CreateRun(
                null,
                command.executionId(),
                command.alarmId(),
                command.mode(),
                tool.id(),
                tool.version(),
                tool.imageDigest(),
                tool.mode().defaultRiskLevel(),
                command.actorPublicId(),
                repositoryIdempotencyKey(command.actorPublicId(), key),
                safeRequest(command),
                1,
                command.expiresAt(),
                command.requestId(),
                command.traceId()));
        Map<String, Object> response = view(run, decision.isApprovalRequired());
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorUserId(), command.actorDisplayName())
                .action("sandbox.run.create")
                .resource("sandbox-run", run.publicId())
                .after(auditView(run, decision.isApprovalRequired()))
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "sandbox-run",
                run.publicId(),
                "sandbox.run.created",
                Map.of(
                        "runId",
                        run.publicId(),
                        "status",
                        run.runStatus().name(),
                        "mode",
                        run.mode().name()),
                command.requestId()));
        idempotencyService.succeed(scope, key, 202, response, "sandbox-run", run.publicId());
        return CommandResult.executed(response, 202);
    }

    @Transactional
    public Map<String, Object> cancel(CancelCommand command) {
        requireAvailable();
        SandboxRunRepository repository = requiredRepository();
        SandboxRunRecord before = repository
                .findByPublicId(command.runId())
                .orElseThrow(() -> new SandboxRunCommandException(
                        SandboxRunCommandException.Code.NOT_FOUND, "Sandbox run not found: " + command.runId()));
        if (!repository.cancel(command.runId(), command.expectedVersion(), command.now())) {
            throw new SandboxRunCommandException(
                    SandboxRunCommandException.Code.VERSION_CONFLICT,
                    "Sandbox run was modified or is already terminal");
        }
        SandboxRunRecord after = repository.findByPublicId(command.runId()).orElseThrow();
        Map<String, Object> response = view(after, false);
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorUserId(), command.actorDisplayName())
                .action("sandbox.run.cancel")
                .resource("sandbox-run", after.publicId())
                .before(auditView(before, false))
                .after(auditView(after, false))
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        outboxWriter.enqueue(OutboxWriter.OutboxEvent.of(
                "sandbox-run",
                after.publicId(),
                "sandbox.run.cancelled",
                Map.of("runId", after.publicId(), "status", after.runStatus().name()),
                command.requestId()));
        return response;
    }

    public static Map<String, Object> view(SandboxRunRecord run, boolean approvalRequired) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", run.publicId());
        value.put("executionId", run.executionPublicId());
        value.put("alarmId", run.alarmPublicId());
        value.put("mode", run.mode().name());
        value.put("toolId", run.toolId());
        value.put("toolVersion", run.toolVersion());
        value.put("status", run.runStatus().name());
        value.put("cleanupStatus", run.cleanupStatus().name());
        value.put("stage", run.stage());
        value.put("progress", run.progress());
        value.put("riskLevel", run.riskLevel().name());
        value.put("approvalRequired", approvalRequired);
        value.put("errorCode", run.errorCode());
        value.put("errorSummary", run.errorSummary());
        value.put("version", run.version());
        value.put("createdAt", run.createdAt());
        value.put("startedAt", run.startedAt());
        value.put("finishedAt", run.finishedAt());
        return value;
    }

    private SandboxToolSpec resolveTool(CreateCommand command) {
        SandboxToolCatalog catalog = toolCatalogProvider.getIfAvailable();
        Optional<SandboxToolSpec> tool = catalog == null
                ? Optional.empty()
                : catalog.find(command.toolId(), command.toolVersion(), command.mode());
        return tool.orElseThrow(() -> invalid("Unknown sandbox tool or version"));
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new SandboxRunCommandException(
                    SandboxRunCommandException.Code.SERVICE_UNAVAILABLE, "Sandbox command service is not available");
        }
    }

    private SandboxRunRepository requiredRepository() {
        SandboxRunRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null || !repository.isAvailable()) {
            throw new SandboxRunCommandException(
                    SandboxRunCommandException.Code.SERVICE_UNAVAILABLE, "Sandbox repository is not available");
        }
        return repository;
    }

    private static void validate(CreateCommand command) {
        if (command == null
                || command.mode() == null
                || blank(command.toolId())
                || blank(command.toolVersion())
                || command.actorUserId() <= 0
                || blank(command.actorPublicId())
                || command.expiresAt() == null
                || !command.expiresAt().isAfter(Instant.now())) {
            throw invalid("mode, toolId, toolVersion, actor and a future expiresAt are required");
        }
    }

    private static Map<String, Object> safeRequest(CreateCommand command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requestedBy", command.actorPublicId());
        putIfPresent(value, "executionId", command.executionId());
        putIfPresent(value, "alarmId", command.alarmId());
        value.put("approvalWaiverRequested", command.approvalWaiverRequested());
        value.put("generatedCodeAutoRun", command.generatedCodeAutoRun());
        return value;
    }

    private static Map<String, Object> auditView(SandboxRunRecord run, boolean approvalRequired) {
        Map<String, Object> value = view(run, approvalRequired);
        value.remove("errorSummary");
        return value;
    }

    private Map<String, Object> parseResponse(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored sandbox response is invalid", ex);
        }
    }

    private static String canonical(CreateCommand command) {
        return "sandbox|" + command.mode() + "|" + command.toolId().trim() + "|"
                + command.toolVersion().trim()
                + "|" + safe(command.executionId()) + "|" + safe(command.alarmId()) + "|" + command.expiresAt()
                + "|" + command.generatedCodeAutoRun() + "|" + command.approvalWaiverRequested();
    }

    private static String repositoryIdempotencyKey(String actorPublicId, String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((actorPublicId + "\\n" + key).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot derive sandbox idempotency key", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (!blank(value)) {
            values.put(key, value);
        }
    }

    private static SandboxRunCommandException invalid(String message) {
        return new SandboxRunCommandException(SandboxRunCommandException.Code.INVALID, message);
    }

    public record CreateCommand(
            SandboxRunMode mode,
            String toolId,
            String toolVersion,
            String executionId,
            String alarmId,
            boolean generatedCodeAutoRun,
            boolean approvalWaiverRequested,
            Instant expiresAt,
            long actorUserId,
            String actorPublicId,
            String actorDisplayName,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent) {}

    public record CancelCommand(
            String runId,
            long expectedVersion,
            Instant now,
            long actorUserId,
            String actorDisplayName,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent) {}

    public record CommandResult(Map<String, Object> data, int httpStatus, boolean replayed) {
        public static CommandResult executed(Map<String, Object> data, int httpStatus) {
            return new CommandResult(data, httpStatus, false);
        }

        public static CommandResult replay(Map<String, Object> data, Integer httpStatus) {
            return new CommandResult(data, httpStatus == null ? 202 : httpStatus, true);
        }
    }
}
