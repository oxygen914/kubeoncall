package com.kubeoncall.rag.runbook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.common.config.KubeOnCallProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RunbookCatalog {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final KubeOnCallProperties properties;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public RunbookCatalog(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public List<RunbookAsset> load() {
        String location = properties.getRag().getRunbookLocation();
        try {
            Resource[] resources = resolver.getResources(location);
            List<RunbookAsset> assets = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (Resource resource : resources) {
                RunbookAsset asset = parse(resource);
                if (!ids.add(asset.runbookId())) {
                    throw new IllegalStateException("Duplicate runbookId: " + asset.runbookId());
                }
                assets.add(asset);
            }
            return assets.stream().sorted(Comparator.comparing(RunbookAsset::runbookId)).toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load runbooks from " + location, ex);
        }
    }

    private RunbookAsset parse(Resource resource) throws Exception {
        String raw = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!raw.startsWith("---\n")) {
            throw new IllegalArgumentException("Runbook missing YAML frontmatter: " + resource.getFilename());
        }
        int end = raw.indexOf("\n---", 4);
        if (end < 0) {
            throw new IllegalArgumentException("Runbook frontmatter is not closed: " + resource.getFilename());
        }
        Map<String, Object> frontmatter = yamlMapper.readValue(raw.substring(4, end), MAP_TYPE);
        int bodyStart = raw.indexOf('\n', end + 4);
        String body = bodyStart < 0 ? "" : raw.substring(bodyStart + 1).trim();
        String runbookId = required(frontmatter, "runbookId", resource);
        String title = required(frontmatter, "title", resource);
        String category = required(frontmatter, "category", resource);
        String version = required(frontmatter, "version", resource);
        if (body.isBlank()) {
            throw new IllegalArgumentException("Runbook body is empty: " + resource.getFilename());
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        frontmatter.forEach((key, value) -> {
            if (value != null && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
                metadata.put(key, String.valueOf(value).trim());
            }
        });
        metadata.put("runbookId", runbookId);
        metadata.put("category", category);
        metadata.put("runbook_version", version);
        metadata.put("dataset_version", version);
        metadata.put("document_type", "runbook");
        metadata.put("source_type", "runbook");
        return new RunbookAsset(
                runbookId, title, body, resource.getFilename(), metadata);
    }

    private String required(Map<String, Object> metadata, String key, Resource resource) {
        Object value = metadata == null ? null : metadata.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "Runbook " + resource.getFilename() + " missing " + key);
        }
        return text;
    }
}
