package com.kubeoncall.skill;

import com.kubeoncall.domain.task.RiskLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Skill(
        String id,
        String name,
        String version,
        SkillSource source,
        String skillPath,
        String description,
        List<String> triggers,
        List<String> services,
        List<String> resourceTypes,
        RiskLevel maxRisk,
        List<String> toolWhitelist,
        String body,
        Map<String, Object> metadata
) {

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("name", name);
        summary.put("version", version);
        summary.put("source", source == null ? null : source.name());
        summary.put("skillPath", skillPath);
        summary.put("description", description);
        summary.put("triggers", triggers);
        summary.put("services", services);
        summary.put("resourceTypes", resourceTypes);
        summary.put("maxRisk", maxRisk == null ? null : maxRisk.name());
        summary.put("toolWhitelist", toolWhitelist);
        return summary;
    }
}
