package com.kubeoncall.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;

public record Skill(
        String id,
        String name,
        String version,
        SkillSource source,
        String skillPath,
        String description,
        List<String> triggers,
        List<String> services,
        List<String> resourceTypes,
        List<String> alertNames,
        List<String> metricNames,
        List<String> runbookIds,
        List<String> categories,
        List<TaskType> applicableTasks,
        List<String> tags,
        RiskLevel maxRisk,
        List<String> toolWhitelist,
        String body,
        Map<String, Object> metadata) {

    public Skill {
        triggers = safe(triggers);
        services = safe(services);
        resourceTypes = safe(resourceTypes);
        alertNames = safe(alertNames);
        metricNames = safe(metricNames);
        runbookIds = safe(runbookIds);
        categories = safe(categories).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        applicableTasks = applicableTasks == null ? List.of() : List.copyOf(applicableTasks);
        tags = safe(tags).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        toolWhitelist = safe(toolWhitelist);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public Skill(
            String id,
            String name,
            String version,
            SkillSource source,
            String skillPath,
            String description,
            List<String> triggers,
            List<String> services,
            List<String> resourceTypes,
            List<TaskType> applicableTasks,
            List<String> tags,
            RiskLevel maxRisk,
            List<String> toolWhitelist,
            String body,
            Map<String, Object> metadata) {
        this(
                id,
                name,
                version,
                source,
                skillPath,
                description,
                triggers,
                services,
                resourceTypes,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                applicableTasks,
                tags,
                maxRisk,
                toolWhitelist,
                body,
                metadata);
    }

    public Skill(
            String id,
            String name,
            String version,
            SkillSource source,
            String skillPath,
            String description,
            List<String> triggers,
            List<String> services,
            List<String> resourceTypes,
            RiskLevel maxRisk,
            List<String> toolWhitelist,
            String body,
            Map<String, Object> metadata) {
        this(
                id,
                name,
                version,
                source,
                skillPath,
                description,
                triggers,
                services,
                resourceTypes,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                maxRisk,
                toolWhitelist,
                body,
                metadata);
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", id);
        summary.put("name", name);
        summary.put("version", version);
        summary.put("source", source == null ? null : source.name());
        summary.put("skillPath", skillPath);
        summary.put("description", description);
        summary.put("triggers", triggers);
        summary.put("services", services);
        summary.put("resourceTypes", resourceTypes);
        summary.put("alertNames", alertNames);
        summary.put("metricNames", metricNames);
        summary.put("runbookIds", runbookIds);
        summary.put("categories", categories);
        summary.put("applicableTasks", applicableTasks.stream().map(Enum::name).toList());
        summary.put("tags", tags);
        summary.put("maxRisk", maxRisk == null ? null : maxRisk.name());
        summary.put("toolWhitelist", toolWhitelist);
        return summary;
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
