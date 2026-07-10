package com.kubeoncall.alarm.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.alarm.domain.AlarmAction;
import com.kubeoncall.alarm.domain.AlarmCondition;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads alarm policies from a YAML file and validates them at startup.
 *
 * <p>The default location is {@code classpath:alarm-policies.yml}; it can be overridden via
 * {@code kubeoncall.alarm.policy-location}. Policies are validated for: unique ids, legal severity,
 * non-null runbook id, and non-null threshold when an operator is present. A validation failure is
 * fatal at startup so misconfigured policies cannot silently degrade the alarm pipeline.
 */
@Component
public class YamlAlarmPolicyRepository implements AlarmPolicyRepository {

    private static final Logger log = LoggerFactory.getLogger(YamlAlarmPolicyRepository.class);

    private final String policyLocation;
    private final boolean enabled;
    private final AlarmSeverity defaultSeverity;
    private final List<AlarmPolicy> policies = new ArrayList<>();
    private final Map<String, AlarmPolicy> byId = new ConcurrentHashMap<>();
    private final Map<String, AlarmPolicy> byName = new ConcurrentHashMap<>();

    @Autowired
    public YamlAlarmPolicyRepository(KubeOnCallProperties properties) {
        KubeOnCallProperties.Alarm alarm = properties.getAlarm();
        this.policyLocation = alarm.getPolicyLocation();
        this.enabled = alarm.isEnabled();
        AlarmSeverity parsed = AlarmSeverity.fromRaw(alarm.getDefaultSeverity());
        this.defaultSeverity = parsed == null ? AlarmSeverity.P3 : parsed;
    }

    /** Test-only constructor that bypasses configuration. */
    public YamlAlarmPolicyRepository(String policyLocation, boolean enabled, AlarmSeverity defaultSeverity) {
        this.policyLocation = policyLocation;
        this.enabled = enabled;
        this.defaultSeverity = defaultSeverity;
    }

    @PostConstruct
    public void load() {
        policies.clear();
        byId.clear();
        byName.clear();
        if (!enabled) {
            log.info("Alarm policy engine is disabled (kubeoncall.alarm.enabled=false); no policies loaded");
            return;
        }
        Resource resource = resolveResource(policyLocation);
        if (!resource.exists()) {
            log.warn("Alarm policy file not found at {}; alarm policy engine will run with no policies", policyLocation);
            return;
        }
        PolicyFile file;
        try (InputStream in = resource.getInputStream()) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            file = mapper.readValue(in, PolicyFile.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load alarm policies from " + policyLocation, ex);
        }
        if (file == null || file.policies() == null) {
            log.info("Alarm policy file {} contained no policies", policyLocation);
            return;
        }
        List<AlarmPolicy> loaded = new ArrayList<>();
        for (PolicyDto dto : file.policies()) {
            AlarmPolicy policy = toPolicy(dto);
            validate(policy, loaded);
            loaded.add(policy);
        }
        for (AlarmPolicy p : loaded) {
            this.policies.add(p);
            byId.put(p.id(), p);
            byName.put(p.name(), p);
        }
        log.info("Loaded {} alarm policies from {}", loaded.size(), policyLocation);
    }

    AlarmSeverity defaultSeverity() {
        return defaultSeverity;
    }

    @Override
    public List<AlarmPolicy> findAll() {
        return List.copyOf(policies);
    }

    @Override
    public Optional<AlarmPolicy> findById(String policyId) {
        return Optional.ofNullable(byId.get(policyId));
    }

    @Override
    public Optional<AlarmPolicy> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    private AlarmPolicy toPolicy(PolicyDto dto) {
        AlarmResourceType resourceType = dto.resourceType() == null ? null : AlarmResourceType.fromRaw(dto.resourceType());
        AlarmSeverity severity = null;
        if (dto.severity() != null && !dto.severity().isBlank()) {
            severity = AlarmSeverity.fromRaw(dto.severity());
            if (severity == null) {
                throw new IllegalStateException("Alarm policy " + dto.id() + " has an invalid severity: " + dto.severity());
            }
        }
        if (severity == null) {
            severity = defaultSeverity;
        }
        AlarmCondition condition = new AlarmCondition(
                dto.name(),
                dto.metricName(),
                resourceType,
                dto.operator(),
                dto.threshold(),
                dto.for_() != null ? dto.for_() : dto.forDuration(),
                dto.matchLabels());
        AlarmAction actions = dto.actions() == null
                ? new AlarmAction(null, null, false, false, List.of())
                : new AlarmAction(
                        dto.actions().workflowTemplate(),
                        dto.actions().notificationChannel(),
                        dto.actions().approvalRequiredForActions(),
                        dto.actions().autoSilence(),
                        dto.actions().allowedTools());
        return new AlarmPolicy(
                dto.id(),
                dto.name(),
                dto.category(),
                dto.metricName(),
                resourceType,
                severity,
                condition,
                dto.promql(),
                dto.window() != null ? dto.window() : dto.for_(),
                dto.recover(),
                dto.runbookId() != null ? dto.runbookId() : runbookFromLabels(dto.labels()),
                dto.owner(),
                actions,
                dto.labels() == null ? Map.of() : dto.labels(),
                dto.ragFilters() == null ? Map.of() : dto.ragFilters());
    }

    private void validate(AlarmPolicy policy, List<AlarmPolicy> alreadyLoaded) {
        if (policy.id() == null || policy.id().isBlank()) {
            throw new IllegalStateException("Alarm policy is missing an id: " + policy.name());
        }
        if (alreadyLoaded.stream().anyMatch(p -> p.id().equals(policy.id()))) {
            throw new IllegalStateException("Duplicate alarm policy id: " + policy.id());
        }
        if (policy.name() == null || policy.name().isBlank()) {
            throw new IllegalStateException("Alarm policy " + policy.id() + " is missing a name");
        }
        if (policy.severity() == null) {
            throw new IllegalStateException("Alarm policy " + policy.id() + " has an invalid severity");
        }
        if (policy.runbookId() == null || policy.runbookId().isBlank()) {
            throw new IllegalStateException("Alarm policy " + policy.id() + " must declare a runbookId");
        }
        if (policy.condition().threshold() == null && (policy.condition().operator() != null && !policy.condition().operator().isBlank())) {
            throw new IllegalStateException("Alarm policy " + policy.id() + " declares an operator without a threshold");
        }
    }

    private static String runbookFromLabels(Map<String, String> labels) {
        if (labels == null) {
            return null;
        }
        return labels.get("runbookId");
    }

    private static Resource resolveResource(String location) {
        if (location == null || location.isBlank()) {
            return new ClassPathResource("alarm-policies.yml");
        }
        if (location.startsWith("classpath:")) {
            return new ClassPathResource(location.substring("classpath:".length()));
        }
        org.springframework.core.io.FileSystemResource fs = new org.springframework.core.io.FileSystemResource(location);
        return fs.exists() ? fs : new ClassPathResource(location);
    }

    // ---- YAML binding DTOs ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PolicyFile(String version, List<PolicyDto> policies) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PolicyDto(
            String id,
            String name,
            String category,
            String metricName,
            String resourceType,
            String severity,
            String operator,
            Double threshold,
            @com.fasterxml.jackson.annotation.JsonProperty("for") String for_,
            String forDuration,
            String promql,
            String window,
            String recover,
            String runbookId,
            String owner,
            Map<String, String> matchLabels,
            Map<String, String> labels,
            Map<String, String> ragFilters,
            ActionDto actions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActionDto(
            String workflowTemplate,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") String notificationChannel,
            boolean approvalRequiredForActions,
            boolean autoSilence,
            List<String> allowedTools
    ) {
        ActionDto {
            if (allowedTools == null) {
                allowedTools = List.of();
            }
        }
    }

    /** Allows the caller to load an arbitrary classpath resource directly (for tests). */
    public static YamlAlarmPolicyRepository loadFromClasspath(String classpathLocation, AlarmSeverity defaultSeverity) {
        YamlAlarmPolicyRepository repo = new YamlAlarmPolicyRepository("classpath:" + classpathLocation, true, defaultSeverity);
        repo.load();
        return repo;
    }
}
