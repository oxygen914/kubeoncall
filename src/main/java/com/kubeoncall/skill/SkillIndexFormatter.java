package com.kubeoncall.skill;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Renders the bounded, always-on Skill index exposed to the planner. */
public final class SkillIndexFormatter {

    static final int MAX_SKILLS = 20;
    static final int MAX_DESCRIPTION_CHARS = 500;
    static final int MAX_INDEX_BYTES = 4096;

    public String format(List<Skill> enabledSkills) {
        if (enabledSkills == null || enabledSkills.isEmpty()) {
            return "No skills registered.";
        }
        StringBuilder result = new StringBuilder("Registered skills:\n");
        enabledSkills.stream().limit(MAX_SKILLS).forEach(skill -> appendIfFits(result, line(skill)));
        appendIfFits(result, "Load a relevant skill through load_skill before using its full instructions.\n");
        return result.toString().trim();
    }

    private String line(Skill skill) {
        return "- " + skill.id() + ": " + truncate(skill.description(), MAX_DESCRIPTION_CHARS) + " triggers="
                + skill.triggers() + " tags=" + skill.tags() + " applicableTasks=" + skill.applicableTasks()
                + " maxRisk=" + skill.maxRisk() + "\n";
    }

    private void appendIfFits(StringBuilder result, String value) {
        if (byteLength(result.toString()) + byteLength(value) <= MAX_INDEX_BYTES) {
            result.append(value);
        }
    }

    private String truncate(String value, int maxChars) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars - 3) + "...";
    }

    private int byteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
