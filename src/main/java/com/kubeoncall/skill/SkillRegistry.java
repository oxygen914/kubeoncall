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
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private volatile List<Skill> skills = List.of();
    private volatile List<String> loadErrors = List.of();

    public SkillRegistry(KubeOnCallProperties properties, SkillFrontmatterParser parser, SkillStateStore stateStore) {
        this.properties = properties;
        this.parser = parser;
        this.stateStore = stateStore;
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
        skills =
                loaded.values().stream().sorted(Comparator.comparing(Skill::id)).toList();
        loadErrors = List.copyOf(errors);
        int overrides = Math.max(0, builtinCount + projectLoaded - skills.size());
        log.info(
                "Loaded {} KubeOnCall skills (project overrides={}, errors={})",
                skills.size(),
                overrides,
                errors.size());
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
                    target.put(skill.id(), skill);
                    loaded++;
                } catch (Exception ex) {
                    String error = source + ":" + resource.getDescription() + ": " + ex.getMessage();
                    errors.add(error);
                    log.warn("Failed to load skill {}", resource.getDescription(), ex);
                }
            }
        } catch (Exception ex) {
            String error = source + ":" + location + ": " + ex.getMessage();
            errors.add(error);
            log.warn("Failed to scan skill resources from {}", location, ex);
        }
        return loaded;
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

    public List<Map<String, Object>> index() {
        java.util.Set<String> disabled = stateStore.disabledIds();
        return skills.stream()
                .map(skill -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", skill.id());
                    item.put("name", skill.name());
                    item.put("description", skill.description());
                    item.put("triggers", skill.triggers());
                    item.put("services", skill.services());
                    item.put(
                            "maxRisk",
                            skill.maxRisk() == null ? null : skill.maxRisk().name());
                    item.put("version", skill.version());
                    item.put(
                            "source",
                            skill.source() == null ? null : skill.source().name());
                    item.put("skillPath", skill.skillPath());
                    item.put("enabled", !disabled.contains(skill.id()));
                    return item;
                })
                .toList();
    }

    public String indexForPrompt() {
        List<Skill> enabledSkills = all();
        if (enabledSkills.isEmpty()) {
            return "No skills registered.";
        }
        StringBuilder builder = new StringBuilder("Registered skills:\n");
        for (Skill skill : enabledSkills) {
            builder.append("- ")
                    .append(skill.id())
                    .append(": ")
                    .append(skill.description())
                    .append(" triggers=")
                    .append(skill.triggers())
                    .append(" maxRisk=")
                    .append(skill.maxRisk())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    public List<String> loadErrors() {
        return loadErrors;
    }

    public record ReloadResult(int loaded, int projectOverrides, List<String> errors) {}
}
