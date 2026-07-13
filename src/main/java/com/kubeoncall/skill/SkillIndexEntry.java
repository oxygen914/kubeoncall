package com.kubeoncall.skill;

import java.util.List;

/** Stable representation of a registered skill for administration and API consumers. */
public record SkillIndexEntry(
        String id,
        String name,
        String description,
        List<String> triggers,
        List<String> services,
        String maxRisk,
        String version,
        String source,
        String skillPath,
        boolean enabled) {

    public SkillIndexEntry {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        services = services == null ? List.of() : List.copyOf(services);
    }
}
