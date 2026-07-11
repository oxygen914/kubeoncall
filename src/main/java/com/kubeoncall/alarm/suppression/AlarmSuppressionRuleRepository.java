package com.kubeoncall.alarm.suppression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class AlarmSuppressionRuleRepository {

    private final Resource resource;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    private volatile String activeVersion = "unloaded";
    private volatile List<AlarmSuppressionRule> rules = List.of();

    public AlarmSuppressionRuleRepository(
            @Value("${kubeoncall.alarm.suppression-rules-location:classpath:alarm-suppression-rules.yml}")
            Resource resource) {
        this.resource = resource;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public synchronized ReloadResult reload() {
        String previous = activeVersion;
        try (InputStream input = resource.getInputStream()) {
            RuleFile parsed = parse(input);
            validate(parsed);
            List<AlarmSuppressionRule> replacement = List.copyOf(parsed.rules());
            rules = replacement;
            activeVersion = parsed.version().trim();
            return new ReloadResult(previous, activeVersion, replacement.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load alarm suppression rules", ex);
        }
    }

    RuleFile parse(InputStream input) throws IOException {
        return yamlMapper.readValue(input, RuleFile.class);
    }

    public List<AlarmSuppressionRule> findAll() {
        return rules;
    }

    public String activeVersion() {
        return activeVersion;
    }

    private void validate(RuleFile file) {
        if (file == null || file.version() == null || file.version().isBlank()) {
            throw new IllegalArgumentException("suppression rule version is required");
        }
        if (file.rules() == null) {
            throw new IllegalArgumentException("suppression rules must not be null");
        }
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (AlarmSuppressionRule rule : file.rules()) {
            if (rule == null || rule.id() == null || rule.id().isBlank()) {
                throw new IllegalArgumentException("suppression rule id is required");
            }
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("duplicate suppression rule id: " + rule.id());
            }
            if (rule.source() == null || rule.target() == null) {
                throw new IllegalArgumentException("source and target are required for rule " + rule.id());
            }
            if (rule.correlateBy().isEmpty()) {
                throw new IllegalArgumentException("correlateBy is required for rule " + rule.id());
            }
            if (rule.ttlSeconds() < 60) {
                throw new IllegalArgumentException("ttlSeconds must be at least 60 for rule " + rule.id());
            }
        }
    }

    record RuleFile(String version, List<AlarmSuppressionRule> rules) {
    }

    public record ReloadResult(String previousVersion, String activeVersion, int ruleCount) {
    }
}
