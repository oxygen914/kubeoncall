package com.kubeoncall.skill;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.kubeoncall.audit.OperationAuditWriter;
import com.kubeoncall.audit.OutboxWriter;
import com.kubeoncall.audit.OutboxWriter.OutboxEvent;
import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;
import com.kubeoncall.task.AsyncTaskRecord;
import com.kubeoncall.task.AsyncTaskRepository;

/** MySQL command/read facade for Skill discovery, activation and durable registry reloads. */
@Service
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SkillGovernanceService {

    public static final String RELOAD_TASK_TYPE = "SKILL_RELOAD";

    private final SkillStateRepository stateRepository;
    private final SkillRegistry registry;
    private final AsyncTaskRepository taskRepository;
    private final OperationAuditWriter auditWriter;
    private final OutboxWriter outboxWriter;

    public SkillGovernanceService(
            SkillStateRepository stateRepository,
            SkillRegistry registry,
            AsyncTaskRepository taskRepository,
            OperationAuditWriter auditWriter,
            OutboxWriter outboxWriter) {
        this.stateRepository = stateRepository;
        this.registry = registry;
        this.taskRepository = taskRepository;
        this.auditWriter = auditWriter;
        this.outboxWriter = outboxWriter;
    }

    public boolean isAvailable() {
        return stateRepository.isAvailable()
                && taskRepository.isAvailable()
                && auditWriter.isAvailable()
                && outboxWriter.isAvailable();
    }

    public SkillStateRepository.SkillStatePage list(SkillStateRepository.SkillStateQuery query) {
        requireAvailable();
        return stateRepository.list(query);
    }

    public SkillStateRecord find(String skillId) {
        requireAvailable();
        return stateRepository
                .find(requiredSkillId(skillId))
                .orElseThrow(() -> failure(SkillGovernanceException.Code.NOT_FOUND, "Skill not found: " + skillId));
    }

    @Transactional
    public SkillStateRecord setEnabled(ChangeEnabledCommand command) {
        requireAvailable();
        String skillId = requiredSkillId(command.skillId());
        if (command.expectedVersion() <= 0) {
            throw failure(SkillGovernanceException.Code.INVALID, "Expected Skill version must be positive");
        }
        SkillStateRecord before = stateRepository
                .find(skillId)
                .orElseThrow(() -> failure(SkillGovernanceException.Code.NOT_FOUND, "Skill not found: " + skillId));
        if (before.version() != command.expectedVersion()) {
            throw failure(
                    SkillGovernanceException.Code.VERSION_CONFLICT,
                    "Skill version changed: expected " + command.expectedVersion() + ", current " + before.version());
        }
        if (registry.findByIdIncludingDisabled(skillId).isEmpty()) {
            throw failure(
                    SkillGovernanceException.Code.CONFLICT,
                    "Skill is persisted but is not present in the runtime registry: " + skillId);
        }
        if (before.enabled() == command.enabled()) {
            synchronizeRuntimeAfterCommit(skillId, command.enabled());
            return before;
        }
        if (!stateRepository.setEnabled(skillId, command.expectedVersion(), command.enabled(), command.actorId())) {
            throw failure(
                    SkillGovernanceException.Code.VERSION_CONFLICT, "Skill version changed while updating: " + skillId);
        }
        SkillStateRecord after = stateRepository
                .find(skillId)
                .orElseThrow(() -> failure(
                        SkillGovernanceException.Code.CONFLICT, "Updated Skill state is not readable: " + skillId));
        writeEnabledAudit(command, before, after);
        synchronizeRuntimeAfterCommit(skillId, command.enabled());
        return after;
    }

    @Transactional
    public AsyncTaskRecord requestReload(ReloadCommand command) {
        requireAvailable();
        String taskId = "tsk_" + compactUuid();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestedBy", command.actorId());
        request.put("actorDisplayName", command.actorDisplayName());
        request.put("requestedAt", command.requestedAt().toString());
        AsyncTaskRecord task = taskRepository.create(new AsyncTaskRepository.CreateTask(
                taskId,
                RELOAD_TASK_TYPE,
                "skill",
                "skill_registry",
                "skill-reload:" + compactUuid(),
                "QUEUED",
                request,
                5,
                command.requestedAt(),
                command.requestId(),
                command.traceId()));
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorId(), command.actorDisplayName())
                .action("skill.reload.request")
                .resource("skill_registry", "skill_registry")
                .result("SUCCESS")
                .reason("Skill registry reload queued")
                .after(Map.of("taskId", task.publicId(), "status", task.status()))
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
                        "resourceId", task.resourcePublicId(),
                        "status", task.status()),
                command.requestId()));
        return task;
    }

    private void writeEnabledAudit(ChangeEnabledCommand command, SkillStateRecord before, SkillStateRecord after) {
        auditWriter.write(OperationAuditWriter.builder()
                .actor("USER", command.actorId(), command.actorDisplayName())
                .action("skill.enabled.update")
                .resource("skill", after.publicId())
                .result("SUCCESS")
                .reason(command.enabled() ? "Skill enabled" : "Skill disabled")
                .before(Map.of("skillId", before.skillId(), "enabled", before.enabled(), "version", before.version()))
                .after(Map.of("skillId", after.skillId(), "enabled", after.enabled(), "version", after.version()))
                .requestId(command.requestId())
                .sourceIp(command.sourceIp())
                .userAgent(command.userAgent())
                .build());
    }

    private void synchronizeRuntimeAfterCommit(String skillId, boolean enabled) {
        Runnable synchronization = () -> {
            if (enabled) {
                registry.enable(skillId);
            } else {
                registry.disable(skillId);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronization.run();
                }
            });
            return;
        }
        synchronization.run();
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw failure(SkillGovernanceException.Code.UNAVAILABLE, "Skill governance service is not available");
        }
    }

    private static String requiredSkillId(String value) {
        if (value == null || value.isBlank()) {
            throw failure(SkillGovernanceException.Code.INVALID, "Skill id is required");
        }
        return value.trim();
    }

    private static SkillGovernanceException failure(SkillGovernanceException.Code code, String message) {
        return new SkillGovernanceException(code, message);
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record ChangeEnabledCommand(
            String skillId,
            long expectedVersion,
            boolean enabled,
            Long actorId,
            String actorDisplayName,
            String requestId,
            String sourceIp,
            String userAgent) {}

    public record ReloadCommand(
            Long actorId,
            String actorDisplayName,
            Instant requestedAt,
            String requestId,
            String traceId,
            String sourceIp,
            String userAgent) {}
}
