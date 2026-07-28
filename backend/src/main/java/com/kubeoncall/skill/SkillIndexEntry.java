package com.kubeoncall.skill;

import java.util.List;

/** Stable representation of a registered skill for administration and API consumers. */
public record SkillIndexEntry(
        String id,
        String name,
        String description,
        List<String> triggers,
        List<String> services,
        List<String> resourceTypes,
        List<String> alertNames,
        List<String> metricNames,
        List<String> runbookIds,
        List<String> categories,
        List<String> applicableTasks,
        List<String> tags,
        String maxRisk,
        String version,
        String source,
        String skillPath,
        boolean enabled) {

    public SkillIndexEntry(
            String id,
            String name,
            String description,
            List<String> triggers,
            List<String> services,
            List<String> applicableTasks,
            List<String> tags,
            String maxRisk,
            String version,
            String source,
            String skillPath,
            boolean enabled) {
        this(
                id,
                name,
                description,
                triggers,
                services,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                applicableTasks,
                tags,
                maxRisk,
                version,
                source,
                skillPath,
                enabled);
    }

    public SkillIndexEntry(
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
        this(
                id,
                name,
                description,
                triggers,
                services,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                maxRisk,
                version,
                source,
                skillPath,
                enabled);
    }

    public SkillIndexEntry {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        services = services == null ? List.of() : List.copyOf(services);
        resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
        alertNames = alertNames == null ? List.of() : List.copyOf(alertNames);
        metricNames = metricNames == null ? List.of() : List.copyOf(metricNames);
        runbookIds = runbookIds == null ? List.of() : List.copyOf(runbookIds);
        categories = categories == null ? List.of() : List.copyOf(categories);
        applicableTasks = applicableTasks == null ? List.of() : List.copyOf(applicableTasks);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
