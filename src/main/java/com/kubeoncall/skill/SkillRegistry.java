package com.kubeoncall.skill;

import com.kubeoncall.common.config.KubeOnCallProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final KubeOnCallProperties properties;
    private final SkillFrontmatterParser parser;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private List<Skill> skills = List.of();

    public SkillRegistry(KubeOnCallProperties properties, SkillFrontmatterParser parser) {
        this.properties = properties;
        this.parser = parser;
    }

    @PostConstruct
    public void load() {
        if (!properties.getSkill().isEnabled()) {
            skills = List.of();
            return;
        }
        List<Skill> loaded = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(properties.getSkill().getLocation());
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                loaded.add(parser.parse(resource.getFilename(), content));
            }
        } catch (Exception ex) {
            log.warn("Failed to load skill resources from {}", properties.getSkill().getLocation(), ex);
        }
        skills = loaded.stream()
                .sorted(Comparator.comparing(Skill::id))
                .toList();
        log.info("Loaded {} KubeOnCall skills from {}", skills.size(), properties.getSkill().getLocation());
    }

    public List<Skill> all() {
        return skills;
    }

    public Optional<Skill> findById(String id) {
        return skills.stream().filter(skill -> skill.id().equals(id)).findFirst();
    }

    public List<Map<String, Object>> index() {
        return skills.stream().map(skill -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", skill.id());
            item.put("name", skill.name());
            item.put("description", skill.description());
            item.put("triggers", skill.triggers());
            item.put("services", skill.services());
            item.put("maxRisk", skill.maxRisk() == null ? null : skill.maxRisk().name());
            return item;
        }).toList();
    }

    public String indexForPrompt() {
        if (skills.isEmpty()) {
            return "No skills registered.";
        }
        StringBuilder builder = new StringBuilder("Registered skills:\n");
        for (Skill skill : skills) {
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
}
