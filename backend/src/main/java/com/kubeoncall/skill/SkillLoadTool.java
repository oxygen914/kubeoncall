package com.kubeoncall.skill;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.memory.TokenBudget;

/** Local read-only load_skill tool used after the planner selects an indexed Skill id. */
@Component
public class SkillLoadTool {

    private final SkillRegistry registry;
    private final TokenBudget tokenBudget;

    public SkillLoadTool(SkillRegistry registry, TokenBudget tokenBudget) {
        this.registry = registry;
        this.tokenBudget = tokenBudget;
    }

    public Map<String, Object> load(String skillId, int maxTokens) {
        Skill skill = registry.findById(skillId).orElse(null);
        if (skill == null) {
            return Map.of("status", "not_found", "skillId", skillId == null ? "" : skillId);
        }
        Map<String, Object> result = new LinkedHashMap<>(skill.summary());
        result.put("tool", "load_skill");
        result.put("status", "success");
        result.put("content", tokenBudget.compactText(skill.body(), Math.max(32, maxTokens)));
        result.put("tokenCount", tokenBudget.estimateTokens(String.valueOf(result.get("content"))));
        return Map.copyOf(result);
    }
}
