package com.kubeoncall.skill;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.kubeoncall.domain.task.RiskLevel;

/**
 * Server-side projection of an activated Skill's execution boundary.
 *
 * <p>An absent Skill leaves the existing tool catalog unrestricted. Once a Skill is active, an
 * empty whitelist fails closed and every tool must match an explicit name or namespace wildcard.
 */
public final class SkillExecutionPolicy {

    private SkillExecutionPolicy() {}

    public static ToolAccess toolAccess(Map<String, Object> context) {
        if (context == null) {
            return ToolAccess.unrestricted();
        }
        boolean restricted =
                stringList(context.get("activatedSkillIds")).stream().anyMatch(id -> !id.isBlank());
        return new ToolAccess(restricted, stringList(context.get("activatedSkillToolWhitelist")));
    }

    public static RiskLevel maxRisk(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object value = context.get("activatedSkillMaxRisk");
        if (value instanceof RiskLevel riskLevel) {
            return riskLevel;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return RiskLevel.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean exceedsMaxRisk(RiskLevel taskRisk, RiskLevel maxRisk) {
        return taskRisk != null && maxRisk != null && taskRisk.ordinal() > maxRisk.ordinal();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    public record ToolAccess(boolean restricted, List<String> allowedTools) {

        public ToolAccess {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        }

        public static ToolAccess unrestricted() {
            return new ToolAccess(false, List.of());
        }

        public static ToolAccess restricted(List<String> allowedTools) {
            return new ToolAccess(true, allowedTools);
        }

        public boolean allows(String toolName) {
            if (!restricted) {
                return true;
            }
            if (toolName == null || toolName.isBlank()) {
                return false;
            }
            return allowedTools.stream()
                    .anyMatch(entry -> entry.equals(toolName)
                            || entry.endsWith(".*") && toolName.startsWith(entry.substring(0, entry.length() - 1)));
        }

        public boolean allowsAny(String... toolNames) {
            if (toolNames == null || toolNames.length == 0) {
                return !restricted;
            }
            for (String toolName : toolNames) {
                if (allows(toolName)) {
                    return true;
                }
            }
            return false;
        }
    }
}
