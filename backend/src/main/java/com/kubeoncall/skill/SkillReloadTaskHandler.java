package com.kubeoncall.skill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.kubeoncall.skill.mysql.SkillStateRecord;
import com.kubeoncall.skill.mysql.SkillStateRepository;
import com.kubeoncall.task.worker.AsyncTaskContext;
import com.kubeoncall.task.worker.AsyncTaskHandler;

/** Reloads the runtime Skill registry and projects the resulting state into MySQL. */
@Component
@ConditionalOnProperty(prefix = "kubeoncall", name = "mysql-enabled", havingValue = "true")
public class SkillReloadTaskHandler implements AsyncTaskHandler {

    private final SkillRegistry registry;
    private final SkillStateRepository stateRepository;

    public SkillReloadTaskHandler(SkillRegistry registry, SkillStateRepository stateRepository) {
        this.registry = registry;
        this.stateRepository = stateRepository;
    }

    @Override
    public String taskType() {
        return SkillGovernanceService.RELOAD_TASK_TYPE;
    }

    @Override
    public HandlerResult handle(AsyncTaskContext context) {
        context.requireValidLease();
        SkillRegistry.ReloadResult reload = registry.reload();
        if (!reload.errors().isEmpty()) {
            throw new IllegalStateException("Skill registry reload failed: " + String.join("; ", reload.errors()));
        }
        context.requireValidLease();
        Instant loadedAt = Instant.now();
        Long updatedBy = requestedBy(context.task().request());
        List<String> persisted = registry.index().stream()
                .map(entry -> persist(entry, loadedAt, updatedBy, context))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loaded", reload.loaded());
        result.put("projectOverrides", reload.projectOverrides());
        result.put("persisted", persisted.size());
        result.put("skillIds", persisted);
        return new HandlerResult(result);
    }

    private String persist(SkillIndexEntry entry, Instant loadedAt, Long updatedBy, AsyncTaskContext context) {
        context.requireValidLease();
        Skill skill = registry.findByIdIncludingDisabled(entry.id())
                .orElseThrow(
                        () -> new IllegalStateException("Reloaded Skill disappeared from registry: " + entry.id()));
        SkillStateRecord existing = stateRepository.find(skill.id()).orElse(null);
        boolean enabled = existing == null ? entry.enabled() : existing.enabled();
        if (enabled) {
            registry.enable(skill.id());
        } else {
            registry.disable(skill.id());
        }
        context.requireValidLease();
        stateRepository.upsert(new SkillStateRepository.UpsertSkillState(
                existing == null ? null : existing.publicId(),
                skill.id(),
                skill.version(),
                checksum(skill.body()),
                skill.skillPath(),
                enabled,
                "LOADED",
                null,
                metadata(skill),
                loadedAt,
                updatedBy));
        return skill.id();
    }

    private static Map<String, Object> metadata(Skill skill) {
        Map<String, Object> metadata = new LinkedHashMap<>(skill.metadata());
        metadata.put("name", skill.name());
        metadata.put("description", skill.description());
        metadata.put("source", skill.source() == null ? null : skill.source().name());
        metadata.put("path", skill.skillPath());
        metadata.put("triggers", skill.triggers());
        metadata.put("services", skill.services());
        metadata.put("resourceTypes", skill.resourceTypes());
        metadata.put(
                "applicableTasks",
                skill.applicableTasks().stream().map(Enum::name).toList());
        metadata.put("tags", skill.tags());
        metadata.put("maxRisk", skill.maxRisk() == null ? null : skill.maxRisk().name());
        metadata.put("toolWhitelist", skill.toolWhitelist());
        return metadata;
    }

    private static Long requestedBy(Map<String, Object> request) {
        Object value = request.get("requestedBy");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String checksum(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
