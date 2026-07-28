package com.kubeoncall.skill;

import java.util.Collections;
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
            recordActivation(false, 0, SkillMatcher.MatchSource.NONE, false);
            return SkillActivation.empty();
        }
        SkillMatcher.MatchResult matchResult = matcher.matchResult(request, context, registry.all());
        Map<String, SkillMatcher.SkillMatch> selected = new LinkedHashMap<>();
        matchResult.matches().forEach(match -> selected.put(match.skill().id(), match));
        if (requestedSkillIds != null) {
            requestedSkillIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(registry::findById)
                    .flatMap(java.util.Optional::stream)
                    .map(skill -> matcher.requestedMatch(request, context, skill))
                    .flatMap(java.util.Optional::stream)
                    .forEach(match -> selected.putIfAbsent(match.skill().id(), match));
        }
        List<SkillMatcher.SkillMatch> selectedMatches = selected.values().stream()
                .limit(Math.max(1, properties.getSkill().getMaxActiveSkills()))
                .toList();
        List<Skill> matched =
                selectedMatches.stream().map(SkillMatcher.SkillMatch::skill).toList();
        if (matched.isEmpty()) {
            recordActivation(false, 0, SkillMatcher.MatchSource.NONE, matchResult.candidateConflict());
            return SkillActivation.empty();
        }
        List<Map<String, Object>> summaries =
                selectedMatches.stream().map(this::summary).toList();
        List<String> ids = matched.stream().map(Skill::id).toList();
        List<String> whitelist = combinedWhitelist(matched);
        RiskLevel maxRisk = mostRestrictiveRisk(matched);
        recordActivation(
                true,
                matched.size(),
                selectedMatches.get(0).source(),
                matchResult.candidateConflict() || selectedMatches.size() > 1);
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

    private Map<String, Object> summary(SkillMatcher.SkillMatch match) {
        Map<String, Object> summary = new LinkedHashMap<>(match.skill().summary());
        summary.put("matchSource", match.source().name());
        summary.put("matchScore", match.score());
        return Collections.unmodifiableMap(summary);
    }

    private void recordActivation(
            boolean active, long count, SkillMatcher.MatchSource matchSource, boolean candidateConflict) {
        metricsService.recordSkillActivation(
                active,
                count,
                matchSource == null ? SkillMatcher.MatchSource.NONE.name() : matchSource.name(),
                candidateConflict);
    }
}
