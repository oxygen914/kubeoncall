package com.kubeoncall.alarm.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;

/** Compiles the governed policy catalog into a Prometheus rule group without publishing it. */
@Service
public class AlarmPolicyCompiler {

    private static final Pattern PROMETHEUS_DURATION = Pattern.compile("^[1-9][0-9]*[smhdwy](?:[0-9]+[smhdwy])*$");

    private final YamlAlarmPolicyRepository repository;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public AlarmPolicyCompiler(YamlAlarmPolicyRepository repository) {
        this.repository = repository;
    }

    public CompiledPrometheusRules compileActive() {
        return compile(repository.activeVersion(), repository.findAll());
    }

    public CompiledPrometheusRules compile(String version, List<AlarmPolicy> policies) {
        if (policies == null || policies.isEmpty()) {
            throw new IllegalStateException("No alarm policies are available to compile");
        }
        List<Map<String, Object>> rules = policies.stream().map(this::toRule).toList();
        Map<String, Object> root =
                Map.of("groups", List.of(Map.of("name", "kubeoncall-" + safe(version), "rules", rules)));
        try {
            String content = yamlMapper.writeValueAsString(root);
            return new CompiledPrometheusRules(version, checksum(content), content, rules.size());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to render Prometheus rules", ex);
        }
    }

    private Map<String, Object> toRule(AlarmPolicy policy) {
        validate(policy);
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("severity", policy.severity().name());
        labels.put("owner", policy.owner());
        labels.put("runbook_id", policy.runbookId());
        labels.put("kubeoncall_metric", policy.metricName());
        if (policy.resourceType() != null) {
            labels.put("resource_type", policy.resourceType().name().toLowerCase(java.util.Locale.ROOT));
        }
        labels.putAll(policy.labels());
        LinkedHashMap<String, Object> rule = new LinkedHashMap<>();
        rule.put("alert", policy.name());
        rule.put("expr", policy.promql().trim());
        if (policy.condition().duration() != null
                && !policy.condition().duration().isBlank()) {
            rule.put("for", policy.condition().duration().trim());
        }
        rule.put("labels", labels);
        rule.put(
                "annotations",
                Map.of(
                        "summary", policy.name() + " matched KubeOnCall policy " + policy.id(),
                        "runbook_id", policy.runbookId(),
                        "policy_version", policy.version()));
        return rule;
    }

    private void validate(AlarmPolicy policy) {
        if (blank(policy.promql())) {
            throw new IllegalStateException("Alarm policy " + policy.id() + " must declare PromQL to compile");
        }
        if (policy.severity() == AlarmSeverity.P0 || policy.severity() == AlarmSeverity.P1) {
            if (blank(policy.owner()) || blank(policy.runbookId())) {
                throw new IllegalStateException("P0/P1 policy " + policy.id() + " must declare owner and runbookId");
            }
            if (policy.actions() != null && policy.actions().autoSilence()) {
                throw new IllegalStateException("P0/P1 policy " + policy.id() + " must not enable autoSilence");
            }
        }
        String duration = policy.condition().duration();
        if (duration != null
                && !duration.isBlank()
                && !PROMETHEUS_DURATION.matcher(duration.trim()).matches()) {
            throw new IllegalStateException("Alarm policy " + policy.id() + " has an invalid Prometheus duration");
        }
    }

    private String checksum(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to checksum Prometheus rules", ex);
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unversioned" : value.replaceAll("[^a-zA-Z0-9_.-]+", "_");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CompiledPrometheusRules(String policyVersion, String checksum, String content, int ruleCount) {}
}
