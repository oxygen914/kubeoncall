package com.kubeoncall.skill;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.service.KubeOnCallMetricsService;

@Service
public class SkillActivationService {

    private final KubeOnCallProperties properties;
    private final SkillRegistry registry;
    private final SkillMatcher matcher;
    private final KubeOnCallMetricsService metricsService;
    private final TokenBudget tokenBudget;

    public SkillActivationService(
            KubeOnCallProperties properties,
            SkillRegistry registry,
            SkillMatcher matcher,
            KubeOnCallMetricsService metricsService,
            TokenBudget tokenBudget) {
        this.properties = properties;
        this.registry = registry;
        this.matcher = matcher;
        this.metricsService = metricsService;
        this.tokenBudget = tokenBudget;
    }

    public SkillActivation activate(String request, Map<String, Object> context) {
        return activate(request, context, List.of());
    }

    public SkillActivation activate(String request, Map<String, Object> context, List<String> requestedSkillIds) {
        if (!properties.getSkill().isEnabled()) {
            recordActivation(false, 0);
            return SkillActivation.empty();
        }
        Map<String, Skill> selected = new LinkedHashMap<>();
        matcher.match(request, context, registry.all()).forEach(skill -> selected.put(skill.id(), skill));
        if (requestedSkillIds != null) {
            requestedSkillIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(registry::findById)
                    .flatMap(java.util.Optional::stream)
                    .filter(skill -> matcher.canActivateRequested(request, context, skill))
                    .forEach(skill -> selected.putIfAbsent(skill.id(), skill));
        }
        List<Skill> matched = selected.values().stream()
                .limit(Math.max(1, properties.getSkill().getMaxActiveSkills()))
                .toList();
        if (matched.isEmpty()) {
            recordActivation(false, 0);
            return SkillActivation.empty();
        }
        List<Map<String, Object>> summaries =
                matched.stream().map(Skill::summary).toList();
        List<String> ids = matched.stream().map(Skill::id).toList();
        List<String> whitelist = combinedWhitelist(matched);
        RiskLevel maxRisk = mostRestrictiveRisk(matched);
        recordActivation(true, matched.size());
        return new SkillActivation(
                matched, summaries, ids, whitelist, maxRisk, buildPrompt(matched, whitelist, maxRisk));
    }

    public String indexForPrompt() {
        return registry.indexForPrompt();
    }

    public List<SkillIndexEntry> index() {
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
        builder.append(
                "Activated operational skills. Treat them as experience hints, not proof. Verify current state first.\n");
        builder.append("Combined tool whitelist: ").append(whitelist).append('\n');
        builder.append("Combined maxRisk: ")
                .append(maxRisk == null ? "UNSPECIFIED" : maxRisk.name())
                .append('\n');
        SkillContextBuffer contextBuffer =
                new SkillContextBuffer(properties.getSkill().getMaxActiveSkills());
        skills.forEach(skill -> contextBuffer.push(skill.id(), renderSkill(skill)));
        String loadedContext = contextBuffer.drain();
        if (!loadedContext.isBlank()) {
            builder.append('\n').append(loadedContext).append('\n');
        }
        String prompt = builder.toString().trim();
        int configured = Math.max(64, properties.getSkill().getPromptTokenBudget());
        int unified = Math.max(128, properties.getMemory().getUnifiedContextTokenBudget());
        return tokenBudget.compactText(prompt, Math.min(configured, Math.max(64, unified / 3)));
    }

    private String renderSkill(Skill skill) {
        return "Name: " + skill.name() + "\nDescription: " + skill.description() + "\nMax risk: " + skill.maxRisk()
                + "\nAllowed tools: " + skill.toolWhitelist() + "\n" + skill.body();
    }

    private void recordActivation(boolean active, long count) {
        metricsService.recordSkillActivation(active, count);
    }
}
