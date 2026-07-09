package com.kubeoncall.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.domain.task.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SkillFrontmatterParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public Skill parse(String resourceName, String content) {
        ParsedDocument parsed = splitFrontmatter(content == null ? "" : content);
        Map<String, Object> metadata = parseMetadata(parsed.frontmatter());
        String id = text(metadata, "id");
        String name = text(metadata, "name");
        if (id == null || id.isBlank()) {
            id = slug(defaultString(name, resourceName));
        }
        if (name == null || name.isBlank()) {
            name = id;
        }
        return new Skill(
                id,
                name,
                defaultString(text(metadata, "description"), ""),
                stringList(metadata.get("triggers")),
                stringList(metadata.get("services")),
                stringList(metadata.get("resourceTypes")),
                riskLevel(text(metadata, "maxRisk")),
                stringList(metadata.get("toolWhitelist")),
                parsed.body().trim(),
                metadata
        );
    }

    private ParsedDocument splitFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new ParsedDocument("", normalized);
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return new ParsedDocument("", normalized);
        }
        String frontmatter = normalized.substring(4, end);
        int bodyStart = normalized.indexOf('\n', end + 4);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
        return new ParsedDocument(frontmatter, body);
    }

    private Map<String, Object> parseMetadata(String frontmatter) {
        if (frontmatter == null || frontmatter.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = yamlMapper.readValue(frontmatter, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid skill frontmatter", ex);
        }
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addString(values, item);
            }
        } else {
            addString(values, value);
        }
        return List.copyOf(values);
    }

    private void addString(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            values.add(text);
        }
    }

    private RiskLevel riskLevel(String value) {
        if (value == null || value.isBlank()) {
            return RiskLevel.MEDIUM;
        }
        try {
            return RiskLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return RiskLevel.MEDIUM;
        }
    }

    private String text(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String slug(String value) {
        return value == null ? "skill" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private record ParsedDocument(String frontmatter, String body) {
    }
}
