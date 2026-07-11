package com.kubeoncall.web;

import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.service.ExecutionAuditService;
import com.kubeoncall.skill.SkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.time.Instant;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry registry;
    private final KubeOnCallMetricsService metricsService;
    private final ExecutionAuditService auditService;

    public SkillController(SkillRegistry registry, KubeOnCallMetricsService metricsService) {
        this(registry, metricsService, null);
    }

    @Autowired
    public SkillController(SkillRegistry registry,
                           KubeOnCallMetricsService metricsService,
                           ExecutionAuditService auditService) {
        this.registry = registry;
        this.metricsService = metricsService;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "skills", registry.index(),
                "loadErrors", registry.loadErrors()
        );
    }

    @PostMapping("/reload")
    public SkillRegistry.ReloadResult reload() {
        Instant startedAt = Instant.now();
        try {
            SkillRegistry.ReloadResult result = registry.reload();
            String outcome = result.errors().isEmpty() ? "success" : "partial";
            metricsService.recordSkillGovernance("reload", outcome);
            audit("reload", outcome, "Skill registry reloaded", startedAt, Map.of(
                    "loaded", result.loaded(), "projectOverrides", result.projectOverrides(),
                    "errorCount", result.errors().size()));
            return result;
        } catch (RuntimeException ex) {
            metricsService.recordSkillGovernance("reload", "failed");
            audit("reload", "failed", "Skill registry reload failed", startedAt,
                    Map.of("errorType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    @PostMapping("/{skillId}/disable")
    public Map<String, Object> disable(@PathVariable String skillId) {
        return setEnabled(skillId, false);
    }

    @PostMapping("/{skillId}/enable")
    public Map<String, Object> enable(@PathVariable String skillId) {
        return setEnabled(skillId, true);
    }

    private Map<String, Object> setEnabled(String skillId, boolean enabled) {
        Instant startedAt = Instant.now();
        String operation = enabled ? "enable" : "disable";
        try {
            if (enabled) {
                registry.enable(skillId);
            } else {
                registry.disable(skillId);
            }
            metricsService.recordSkillGovernance(operation, "success");
            audit(operation, "success", "Skill state updated", startedAt,
                    Map.of("skillId", skillId, "enabled", enabled));
            return Map.of("skillId", skillId, "enabled", enabled);
        } catch (RuntimeException ex) {
            metricsService.recordSkillGovernance(operation, "failed");
            audit(operation, "failed", "Skill state update failed", startedAt,
                    Map.of("skillId", skillId == null ? "" : skillId,
                            "enabled", enabled, "errorType", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    private void audit(String operation,
                       String status,
                       String summary,
                       Instant startedAt,
        Map<String, Object> metadata) {
        if (auditService != null) {
            try {
                auditService.recordSkillOperation(operation, status, summary, startedAt, metadata);
            } catch (RuntimeException ignored) {
                // Governance state is authoritative; an audit backend outage is reported separately.
            }
        }
    }
}
