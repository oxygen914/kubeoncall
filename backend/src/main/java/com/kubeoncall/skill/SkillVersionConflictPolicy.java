package com.kubeoncall.skill;

public enum SkillVersionConflictPolicy {
    PREFER_PROJECT,
    HIGHEST_VERSION,
    REJECT;

    public static SkillVersionConflictPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return PREFER_PROJECT;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return PREFER_PROJECT;
        }
    }
}
