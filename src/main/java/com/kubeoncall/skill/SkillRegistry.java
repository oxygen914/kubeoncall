package com.kubeoncall.skill;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final KubeOnCallProperties properties;
    private final SkillFrontmatterParser parser;
    private final SkillStateStore stateStore;
    private final SkillSnapshotStore snapshotStore;
    private final SkillIndexFormatter indexFormatter = new SkillIndexFormatter();
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private volatile List<Skill> skills = List.of();
    private volatile List<String> loadErrors = List.of();

    public SkillRegistry(KubeOnCallProperties properties, SkillFrontmatterParser parser, SkillStateStore stateStore) {
        this(properties, parser, stateStore, null);
    }

    @Autowired
    public SkillRegistry(
            KubeOnCallProperties properties,
            SkillFrontmatterParser parser,
            SkillStateStore stateStore,
            SkillSnapshotStore snapshotStore) {
        this.properties = properties;
        this.parser = parser;
        this.stateStore = stateStore;
        this.snapshotStore = snapshotStore;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public synchronized ReloadResult reload() {
        if (!properties.getSkill().isEnabled()) {
            skills = List.of();
            loadErrors = List.of();
            return new ReloadResult(0, 0, List.of());
        }
        Map<String, Skill> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        loadLocation(properties.getSkill().getLocation(), SkillSource.BUILTIN, loaded, errors);
        int builtinCount = loaded.size();
        int projectLoaded =
                loadLocation(properties.getSkill().getProjectLocation(), SkillSource.PROJECT, loaded, errors);
        List<Skill> loadedSkills =
                loaded.values().stream().sorted(Comparator.comparing(Skill::id)).toList();
        boolean restoredFromSnapshot = false;
        if (loadedSkills.isEmpty() && !errors.isEmpty() && snapshotStore != null) {
            List<Skill> restored = snapshotStore.load();
            if (!restored.isEmpty()) {
                loadedSkills = restored.stream()
                        .sorted(Comparator.comparing(Skill::id))
                        .toList();
                restoredFromSnapshot = true;
            }
        }
        skills = loadedSkills;
        loadErrors = List.copyOf(errors);
        if (!restoredFromSnapshot && !skills.isEmpty() && snapshotStore != null) {
            snapshotStore.save(skills);
        }
        int overrides = Math.max(0, builtinCount + projectLoaded - skills.size());
        log.info(
                "Loaded {} KubeOnCall skills (project overrides={}, errors={})",
                skills.size(),
                overrides,
                errors.size());
        if (restoredFromSnapshot) {
            log.warn("Restored {} KubeOnCall skills from the last valid snapshot", skills.size());
        }
        return new ReloadResult(skills.size(), overrides, loadErrors);
    }

    private int loadLocation(String location, SkillSource source, Map<String, Skill> target, List<String> errors) {
        if (location == null || location.isBlank()) {
            return 0;
        }
        int loaded = 0;
        try {
            Resource[] resources = resolver.getResources(location);
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                try {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    String path = resource.getURI().toString();
                    Skill skill = parser.parse(resource.getFilename(), content, source, path);
                    if (acceptSkill(target, skill, errors)) {
                        loaded++;
                    }
                } catch (Exception ex) {
                    String resourceName = resource.getFilename() == null ? "unknown" : resource.getFilename();
                    errors.add(source + ":" + resourceName + ": skill load failed");
                    log.warn(
                            "Failed to load skill: source={}, resource={}, errorType={}",
                            source,
                            resourceName,
                            ex.getClass().getSimpleName());
                }
            }
        } catch (Exception ex) {
            errors.add(source + ": skill scan failed");
            log.warn(
                    "Failed to scan skill resources: source={}, errorType={}",
                    source,
                    ex.getClass().getSimpleName());
        }
        return loaded;
    }

    private boolean acceptSkill(Map<String, Skill> target, Skill candidate, List<String> errors) {
        Skill existing = target.get(candidate.id());
        if (existing == null) {
            target.put(candidate.id(), candidate);
            return true;
        }
        SkillVersionConflictPolicy policy =
                SkillVersionConflictPolicy.from(properties.getSkill().getVersionConflictPolicy());
        if (policy == SkillVersionConflictPolicy.REJECT) {
            errors.add("CONFLICT:" + candidate.id() + ": rejected by version conflict policy");
            return false;
        }
        Skill selected = policy == SkillVersionConflictPolicy.HIGHEST_VERSION
                ? highestVersion(existing, candidate)
                : preferProjectThenVersion(existing, candidate);
        target.put(candidate.id(), selected);
        return selected == candidate;
    }

    private Skill preferProjectThenVersion(Skill existing, Skill candidate) {
        if (candidate.source() == SkillSource.PROJECT && existing.source() != SkillSource.PROJECT) {
            return candidate;
        }
        if (existing.source() == SkillSource.PROJECT && candidate.source() != SkillSource.PROJECT) {
            return existing;
        }
        return highestVersion(existing, candidate);
    }

    private Skill highestVersion(Skill existing, Skill candidate) {
        int comparison = compareVersion(candidate.version(), existing.version());
        if (comparison > 0) {
            return candidate;
        }
        if (comparison < 0) {
            return existing;
        }
        return candidate.source() == SkillSource.PROJECT ? candidate : existing;
    }

    private int compareVersion(String left, String right) {
        String[] leftParts = normalizedVersion(left).split("\\.");
        String[] rightParts = normalizedVersion(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private String normalizedVersion(String version) {
        return version == null ? "0" : version.trim().replaceFirst("^[vV]", "").replaceAll("[^0-9.]", "");
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.isBlank() ? "0" : part);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public List<Skill> all() {
        java.util.Set<String> disabled = stateStore.disabledIds();
        return skills.stream().filter(skill -> !disabled.contains(skill.id())).toList();
    }

    public Optional<Skill> findById(String id) {
        return all().stream().filter(skill -> skill.id().equals(id)).findFirst();
    }

    public Optional<Skill> findByIdIncludingDisabled(String id) {
        return skills.stream().filter(skill -> skill.id().equals(id)).findFirst();
    }

    public void disable(String id) {
        requireKnown(id);
        stateStore.disable(id);
    }

    public void enable(String id) {
        requireKnown(id);
        stateStore.enable(id);
    }

    private void requireKnown(String id) {
        if (id == null || id.isBlank() || findByIdIncludingDisabled(id).isEmpty()) {
            throw new IllegalArgumentException("Unknown skill id: " + id);
        }
    }

    public List<SkillIndexEntry> index() {
        java.util.Set<String> disabled = stateStore.disabledIds();
        return skills.stream()
                .map(skill -> new SkillIndexEntry(
                        skill.id(),
                        skill.name(),
                        skill.description(),
                        skill.triggers(),
                        skill.services(),
                        skill.applicableTasks().stream().map(Enum::name).toList(),
                        skill.tags(),
                        skill.maxRisk() == null ? null : skill.maxRisk().name(),
                        skill.version(),
                        skill.source() == null ? null : skill.source().name(),
                        skill.skillPath(),
                        !disabled.contains(skill.id())))
                .toList();
    }

    public String indexForPrompt() {
        return indexFormatter.format(all());
    }

    public List<String> loadErrors() {
        return loadErrors;
    }

    public record ReloadResult(int loaded, int projectOverrides, List<String> errors) {}
}
