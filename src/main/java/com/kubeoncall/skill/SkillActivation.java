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
}
