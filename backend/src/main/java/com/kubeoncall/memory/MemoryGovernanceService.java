package com.kubeoncall.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.audit.OutboxWriter.OutboxEvent;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.memory.mysql.MemoryEntryRecord;
import com.kubeoncall.memory.mysql.MemoryEntryRepository;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRecord;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository;
import com.kubeoncall.memory.mysql.MemoryExtractionTaskRepository.CreateExtraction;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;
import com.kubeoncall.task.AsyncTaskRepository.CreateTask;

/** Transactional command path for durable memory governance and extraction tasks. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class MemoryGovernanceService {

    public static final String EXTRACTION_TASK_TYPE = "MEMORY_EXTRACTION";

    private final MemoryEntryRepository memoryRepository;
    private final MemoryExtractionTaskRepository extractionRepository;
    private final AsyncTaskRepository taskRepository;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;
    private final KubeOnCallProperties properties;

    public MemoryGovernanceService(
            MemoryEntryRepository memoryRepository,
            MemoryExtractionTaskRepository extractionRepository,
            AsyncTaskRepository taskRepository,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter,
            KubeOnCallProperties properties) {
        this.memoryRepository = memoryRepository;
        this.extractionRepository = extractionRepository;
        this.taskRepository = taskRepository;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
    }

    public boolean isAvailable() {
        return memoryRepository.isAvailable()
                && extractionRepository.isAvailable()
                && taskRepository.isAvailable()
                && auditWriter.isAvailable()
                && outboxWriter.isAvailable();
    }

    @Transactional
    public StartResult startExtraction(StartCommand command) {
        String extractionId = publicId("mext_");
        String taskId = publicId("tsk_");
        Map<String, Object> request = extractionRequest(command);
        AsyncTaskRecord task;
        try {
            task = taskRepository.create(new CreateTask(
                    taskId,
                    EXTRACTION_TASK_TYPE,
                    "memory_extraction",
                    extractionId,
                    extractionDedupe(command),
                    "queued",
                    request,
                    command.maxAttempts() > 0
                            ? command.maxAttempts()
                            : properties.getMemory().getExtractionMaxAttempts(),
                    command.requestedAt(),
                    command.requestId(),
                    command.traceId()));
        } catch (DuplicateKeyException ex) {
            throw new CommandException(
                    Failure.CONFLICT, "A memory extraction with the same source and dedupe key already exists", ex);
        }
        MemoryExtractionTaskRecord extraction = extractionRepository.create(new CreateExtraction(
                extractionId,
                task.publicId(),
                command.sourceType(),
                command.sourcePublicId(),
                command.dedupeKey(),
                firstNonBlank(command.extractorModel(), properties.getMemory().getLlmExtractionModel()),
                firstNonBlank(command.extractorVersion(), "v1")));

        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorId(), command.actorDisplayName())
                .action("memory.extraction.create")
                .resource("memory_extraction", extraction.publicId())
                .result("SUCCESS")
                .reason("Memory extraction queued")
                .after(Map.of(
                        "taskId", task.publicId(),
                        "sourceType", extraction.sourceType(),
                        "sourcePublicId", extraction.sourcePublicId()))
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
        outboxWriter.enqueue(OutboxEvent.of(
                "task",
                task.publicId(),
                "task.created",
                Map.of(
                        "taskId", task.publicId(),
                        "taskType", task.taskType(),
                        "resourceType", task.resourceType(),
                        "resourceId", extraction.publicId(),
                        "status", task.status()),
                command.requestId()));
        return new StartResult(extraction, task);
    }

    @Transactional
    public MemoryEntryRecord softDelete(ChangeCommand command) {
        MemoryEntryRecord before = requireMemory(command.memoryId());
        if (!memoryRepository.softDelete(
                before.publicId(), command.expectedVersion(), command.reason(), command.occurredAt())) {
            throw new CommandException(Failure.CONFLICT, "Memory version changed or memory is already deleted");
        }
        MemoryEntryRecord after = requireMemory(command.memoryId());
        writeMemoryAudit(command, "memory.delete", before, after);
        return after;
    }

    @Transactional
    public MemoryEntryRecord restore(ChangeCommand command) {
        MemoryEntryRecord before = requireMemory(command.memoryId());
        if (!memoryRepository.restore(before.publicId(), command.expectedVersion())) {
            throw new CommandException(Failure.CONFLICT, "Memory version changed or memory is already active");
        }
        MemoryEntryRecord after = requireMemory(command.memoryId());
        writeMemoryAudit(command, "memory.restore", before, after);
        return after;
    }

    private MemoryEntryRecord requireMemory(String memoryId) {
        return memoryRepository
                .find(memoryId, true)
                .orElseThrow(() -> new CommandException(Failure.NOT_FOUND, "Memory not found: " + memoryId));
    }

    private void writeMemoryAudit(
            ChangeCommand command, String action, MemoryEntryRecord before, MemoryEntryRecord after) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorId(), command.actorDisplayName())
                .action(action)
                .resource("memory", before.publicId())
                .result("SUCCESS")
                .reason(command.reason())
                .before(Map.of("status", before.status(), "version", before.version()))
                .after(Map.of("status", after.status(), "version", after.version()))
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
    }

    private static Map<String, Object> extractionRequest(StartCommand command) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceType", command.sourceType());
        request.put("sourcePublicId", command.sourcePublicId());
        request.put("memoryType", command.memoryType());
        request.put("scope", command.scope());
        request.put("subject", command.subject());
        request.put("content", command.content());
        putIfPresent(request, "service", command.service());
        putIfPresent(request, "resource", command.resource());
        putIfPresent(request, "fingerprint", command.fingerprint());
        request.put("metadata", command.metadata() == null ? Map.of() : command.metadata());
        return request;
    }

    private static String extractionDedupe(StartCommand command) {
        return command.sourceType().trim().toUpperCase() + ":"
                + command.sourcePublicId().trim() + ":" + command.dedupeKey().trim();
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String publicId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    public record StartCommand(
            String sourceType,
            String sourcePublicId,
            String dedupeKey,
            String memoryType,
            String scope,
            String subject,
            String content,
            String service,
            String resource,
            String fingerprint,
            Map<String, String> metadata,
            String extractorModel,
            String extractorVersion,
            int maxAttempts,
            Long actorId,
            String actorDisplayName,
            Instant requestedAt,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent) {}

    public record ChangeCommand(
            String memoryId,
            long expectedVersion,
            String reason,
            Long actorId,
            String actorDisplayName,
            Instant occurredAt,
            String requestId,
            String sourceIp,
            String userAgent) {}

    public record StartResult(MemoryExtractionTaskRecord extraction, AsyncTaskRecord task) {}

    public enum Failure {
        NOT_FOUND,
        CONFLICT
    }

    public static final class CommandException extends RuntimeException {

        private final Failure failure;

        public CommandException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        public CommandException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }
}
