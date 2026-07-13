package com.kubeoncall.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class SkillMatcher {

    private final KubeOnCallProperties properties;

    public SkillMatcher(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public List<Skill> match(String request, Map<String, Object> context, List<Skill> skills) {
        String haystack = haystack(request, context);
        int threshold = Math.max(1, properties.getSkill().getActivationThreshold());
        int limit = Math.max(1, properties.getSkill().getMaxActiveSkills());
        return skills.stream()
                .map(skill -> new ScoredSkill(skill, score(skill, haystack)))
                .filter(scored -> scored.score() >= threshold)
                .sorted(Comparator.comparingInt(ScoredSkill::score).reversed().thenComparing(scored -> scored.skill()
                        .id()))
                .limit(limit)
                .map(ScoredSkill::skill)
                .toList();
    }

    private int score(Skill skill, String haystack) {
        int score = 0;
        for (String trigger : skill.triggers()) {
            if (contains(haystack, trigger)) {
                score += 4;
            }
        }
        for (String service : skill.services()) {
            if (contains(haystack, service)) {
                score += 3;
            }
        }
        for (String resourceType : skill.resourceTypes()) {
            if (contains(haystack, resourceType)) {
                score += 2;
            }
        }
        if (contains(haystack, skill.name())) {
            score += 2;
        }
        return score;
    }

    private boolean contains(String haystack, String needle) {
        return needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT));
    }

    private String haystack(String request, Map<String, Object> context) {
        StringBuilder builder = new StringBuilder(request == null ? "" : request);
        if (context != null) {
            context.forEach((key, value) -> {
                if (value != null) {
                    builder.append(' ').append(key).append('=').append(value);
                }
            });
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private record ScoredSkill(Skill skill, int score) {}
}
