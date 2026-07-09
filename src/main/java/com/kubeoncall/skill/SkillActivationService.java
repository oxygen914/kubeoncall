package com.kubeoncall.skill;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SkillActivationService {

    private final KubeOnCallProperties properties;
    private final SkillRegistry registry;
    private final SkillMatcher matcher;
    private final KubeOnCallMetricsService metricsService;

    @Autowired
    public SkillActivationService(KubeOnCallProperties properties,
                                  SkillRegistry registry,
                                  SkillMatcher matcher,
                                  KubeOnCallMetricsService metricsService) {
        this.properties = properties;
        this.registry = registry;
        this.matcher = matcher;
        this.metricsService = metricsService;
    }

    public SkillActivationService(KubeOnCallProperties properties, SkillRegistry registry, SkillMatcher matcher) {
        this(properties, registry, matcher, null);
    }

    public SkillActivation activate(String request, Map<String, Object> context) {
        if (!properties.getSkill().isEnabled()) {
            recordActivation(false, 0);
            return SkillActivation.empty();
        }
        List<Skill> matched = matcher.match(request, context, registry.all());
        if (matched.isEmpty()) {
            recordActivation(false, 0);
            return SkillActivation.empty();
        }
        List<Map<String, Object>> summaries = matched.stream().map(Skill::summary).toList();
        List<String> ids = matched.stream().map(Skill::id).toList();
        List<String> whitelist = combinedWhitelist(matched);
        RiskLevel maxRisk = mostRestrictiveRisk(matched);
        recordActivation(true, matched.size());
        return new SkillActivation(matched, summaries, ids, whitelist, maxRisk, buildPrompt(matched, whitelist, maxRisk));
    }

    public String indexForPrompt() {
        return registry.indexForPrompt();
    }

    public List<Map<String, Object>> index() {
        return registry.index();
    }

    private List<String> combinedWhitelist(List<Skill> skills) {
        Set<String> tools = new LinkedHashSet<>();
        skills.forEach(skill -> tools.addAll(skill.toolWhitelist()));
        return List.copyOf(tools);
    }

    private RiskLevel mostRestrictiveRisk(List<Skill> skills) {
        return skills.stream()
                .map(Skill::maxRisk)
                .filter(risk -> risk != null)
                .min(Enum::compareTo)
                .orElse(null);
    }

    private String buildPrompt(List<Skill> skills, List<String> whitelist, RiskLevel maxRisk) {
        StringBuilder builder = new StringBuilder();
        builder.append("Activated operational skills. Treat them as experience hints, not proof. Verify current state first.\n");
        builder.append("Combined tool whitelist: ").append(whitelist).append('\n');
        builder.append("Combined maxRisk: ").append(maxRisk == null ? "UNSPECIFIED" : maxRisk.name()).append('\n');
        for (Skill skill : skills) {
            builder.append("\n## ").append(skill.name()).append(" (").append(skill.id()).append(")\n");
            builder.append("Description: ").append(skill.description()).append('\n');
            builder.append("Max risk: ").append(skill.maxRisk()).append('\n');
            builder.append("Allowed tools: ").append(skill.toolWhitelist()).append('\n');
            builder.append(skill.body()).append('\n');
        }
        String prompt = builder.toString().trim();
        int maxChars = Math.max(500, properties.getSkill().getPromptMaxChars());
        if (prompt.length() <= maxChars) {
            return prompt;
        }
        return prompt.substring(0, maxChars - 3) + "...";
    }

    private void recordActivation(boolean active, long count) {
        if (metricsService != null) {
            metricsService.recordSkillActivation(active, count);
        }
    }
}
