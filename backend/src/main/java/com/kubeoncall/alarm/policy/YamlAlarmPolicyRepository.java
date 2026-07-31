package com.kubeoncall.alarm.policy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.alarm.domain.AlarmAction;
import com.kubeoncall.alarm.domain.AlarmCondition;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.common.config.KubeOnCallProperties;

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
    private final int versionHistoryLimit;
    private final AlarmPolicySnapshotStore snapshotStore;
    private volatile List<AlarmPolicy> policies = List.of();
    private volatile Map<String, AlarmPolicy> byId = Map.of();
    private volatile Map<String, AlarmPolicy> byName = Map.of();
    private volatile String activeVersion = "unversioned";
    private volatile Map<String, PolicySnapshot> versionHistory = Map.of();

    public YamlAlarmPolicyRepository(KubeOnCallProperties properties) {
        this(properties, null);
    }

    @Autowired
    public YamlAlarmPolicyRepository(KubeOnCallProperties properties, AlarmPolicySnapshotStore snapshotStore) {
        KubeOnCallProperties.Alarm alarm = properties.getAlarm();
        this.policyLocation = alarm.getPolicyLocation();
        this.enabled = alarm.isEnabled();
        AlarmSeverity parsed = AlarmSeverity.fromRaw(alarm.getDefaultSeverity());
        this.defaultSeverity = parsed == null ? AlarmSeverity.P3 : parsed;
        this.versionHistoryLimit = Math.max(1, alarm.getPolicyVersionHistoryLimit());
        this.snapshotStore = snapshotStore;
    }

    @PostConstruct
    public synchronized void load() {
        if (!enabled) {
            log.info("Alarm policy engine is disabled (kubeoncall.alarm.enabled=false); no policies loaded");
            policies = List.of();
            byId = Map.of();
            byName = Map.of();
            return;
        }
        Resource resource = resolveResource(policyLocation);
        if (!resource.exists()) {
            log.warn(
                    "Alarm policy file not found at {}; alarm policy engine will run with no policies", policyLocation);
            policies = List.of();
            byId = Map.of();
            byName = Map.of();
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
            AlarmPolicy policy = toPolicy(dto, file.version());
            validate(policy, loaded);
            loaded.add(policy);
        }
        Map<String, AlarmPolicy> nextById = new ConcurrentHashMap<>();
        Map<String, AlarmPolicy> nextByName = new ConcurrentHashMap<>();
        loaded.forEach(policy -> {
            nextById.put(policy.id(), policy);
            nextByName.put(policy.name(), policy);
        });
        this.policies = List.copyOf(loaded);
        this.byId = Map.copyOf(nextById);
        this.byName = Map.copyOf(nextByName);
        this.activeVersion = file.version() == null || file.version().isBlank() ? "unversioned" : file.version();
        restoreHistory();
        retainVersion(this.activeVersion, this.policies);
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

    public ReloadResult reload() {
        String previousVersion = activeVersion;
        load();
        return new ReloadResult(previousVersion, activeVersion, policies.size());
    }

    public String activeVersion() {
        return activeVersion;
    }

    /** Immutable in-process policy snapshots support audited dry runs and immediate rollback. */
    public Map<String, PolicySnapshot> versionHistory() {
        return Map.copyOf(versionHistory);
    }

    public List<AlarmPolicy> findAll(String version) {
        PolicySnapshot snapshot = versionHistory.get(version);
        return snapshot == null ? List.of() : snapshot.policies();
    }

    public synchronized PolicySnapshot rollback(String version) {
        PolicySnapshot snapshot = versionHistory.get(version);
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown alarm policy version: " + version);
        }
        String previousVersion = activeVersion;
        this.policies = snapshot.policies();
        this.byId = indexById(snapshot.policies());
        this.byName = indexByName(snapshot.policies());
        this.activeVersion = snapshot.version();
        persistHistory();
        return new PolicySnapshot(snapshot.version(), previousVersion, snapshot.loadedAt(), snapshot.policies());
    }

    /**
     * Allows an upstream canary route to pin an alarm to a loaded version with the
     * {@code kubeoncall_policy_version} label (or metadata {@code policyVersion}).
     */
    public String resolveVersion(com.kubeoncall.alarm.domain.NormalizedAlarmEvent event) {
        String requested = event.labels().get("kubeoncall_policy_version");
        if (requested == null || requested.isBlank()) {
            Object metadataVersion = event.metadata().get("policyVersion");
            requested = metadataVersion == null ? null : String.valueOf(metadataVersion);
        }
        return requested != null && versionHistory.containsKey(requested) ? requested : activeVersion;
    }

    private synchronized void retainVersion(String version, List<AlarmPolicy> snapshotPolicies) {
        LinkedHashMap<String, PolicySnapshot> next = new LinkedHashMap<>(versionHistory);
        next.put(version, new PolicySnapshot(version, null, java.time.Instant.now(), List.copyOf(snapshotPolicies)));
        while (next.size() > versionHistoryLimit) {
            next.remove(next.keySet().iterator().next());
        }
        versionHistory = Map.copyOf(next);
        persistHistory();
    }

    private void restoreHistory() {
        if (snapshotStore == null || !versionHistory.isEmpty()) {
            return;
        }
        Map<String, PolicySnapshot> restored = snapshotStore.load();
        if (restored != null && !restored.isEmpty()) {
            versionHistory = Map.copyOf(restored);
        }
    }

    private void persistHistory() {
        if (snapshotStore != null && !versionHistory.isEmpty()) {
            snapshotStore.save(versionHistory);
        }
    }

    private Map<String, AlarmPolicy> indexById(List<AlarmPolicy> source) {
        Map<String, AlarmPolicy> index = new ConcurrentHashMap<>();
        source.forEach(policy -> index.put(policy.id(), policy));
        return Map.copyOf(index);
    }

    private Map<String, AlarmPolicy> indexByName(List<AlarmPolicy> source) {
        Map<String, AlarmPolicy> index = new ConcurrentHashMap<>();
        source.forEach(policy -> index.put(policy.name(), policy));
        return Map.copyOf(index);
    }

    private AlarmPolicy toPolicy(PolicyDto dto, String version) {
        AlarmResourceType resourceType =
                dto.resourceType() == null ? null : AlarmResourceType.fromRaw(dto.resourceType());
        AlarmSeverity severity = null;
        if (dto.severity() != null && !dto.severity().isBlank()) {
            severity = AlarmSeverity.fromRaw(dto.severity());
            if (severity == null) {
                throw new IllegalStateException(
                        "Alarm policy " + dto.id() + " has an invalid severity: " + dto.severity());
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
                dto.ragFilters() == null ? Map.of() : dto.ragFilters(),
                version);
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
        if (policy.condition().threshold() == null
                && (policy.condition().operator() != null
                        && !policy.condition().operator().isBlank())) {
            throw new IllegalStateException(
                    "Alarm policy " + policy.id() + " declares an operator without a threshold");
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
        org.springframework.core.io.FileSystemResource fs =
                new org.springframework.core.io.FileSystemResource(location);
        return fs.exists() ? fs : new ClassPathResource(location);
    }

    // ---- YAML binding DTOs ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PolicyFile(String version, List<PolicyDto> policies) {}

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

            @com.fasterxml.jackson.annotation.JsonProperty("for")
            String for_,

            String forDuration,
            String promql,
            String window,
            String recover,
            String runbookId,
            String owner,
            Map<String, String> matchLabels,
            Map<String, String> labels,
            Map<String, String> ragFilters,
            ActionDto actions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActionDto(
            String workflowTemplate,

            @com.fasterxml.jackson.annotation.JsonProperty("notify")
            String notificationChannel,

            boolean approvalRequiredForActions,
            boolean autoSilence,
            List<String> allowedTools) {
        ActionDto {
            if (allowedTools == null) {
                allowedTools = List.of();
            }
        }
    }

    public record ReloadResult(String previousVersion, String activeVersion, int policyCount) {}

    public record PolicySnapshot(
            String version, String previousVersion, java.time.Instant loadedAt, List<AlarmPolicy> policies) {}
}
