package com.kubeoncall.skill;

import java.util.List;
import java.util.Map;

import com.kubeoncall.domain.task.RiskLevel;

public record SkillActivation(
        List<Skill> skills,
        List<Map<String, Object>> skillSummaries,
        List<String> skillIds,
        List<String> toolWhitelist,
        RiskLevel maxRisk,
        String prompt) {

    public static SkillActivation empty() {
        return new SkillActivation(List.of(), List.of(), List.of(), List.of(), null, "");
    }

    public boolean active() {
        return skills != null && !skills.isEmpty();
    }

    public List<String> matchSources() {
        if (skillSummaries == null) {
            return List.of();
        }
        return skillSummaries.stream()
                .map(summary -> summary.get("matchSource"))
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toList();
    }
}
