package com.kubeoncall.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmPolicyCompiler;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;

class AlarmRuleAlignmentTest {

    private static final int ACTIVE_RULE_COUNT = 27;

    @Test
    void prometheusRulesShouldMatchActivePoliciesFieldByField() throws IOException {
        YamlAlarmPolicyRepository repository =
                AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies-node-mvp.yml", AlarmSeverity.P3);
        List<PrometheusRule> rules = loadPrometheusRules();

        assertEquals(ACTIVE_RULE_COUNT, repository.findAll().size());
        assertEquals(ACTIVE_RULE_COUNT, rules.size());

        Map<String, PrometheusRule> rulesByName = new LinkedHashMap<>();
        for (PrometheusRule rule : rules) {
            assertTrue(
                    rulesByName.putIfAbsent(rule.alert(), rule) == null,
                    () -> "duplicate Prometheus alert: " + rule.alert());
        }

        Set<String> policyNames =
                repository.findAll().stream().map(AlarmPolicy::name).collect(Collectors.toSet());
        assertEquals(policyNames, rulesByName.keySet(), "Prometheus alerts and active policies must be one-to-one");

        for (AlarmPolicy policy : repository.findAll()) {
            PrometheusRule rule = rulesByName.get(policy.name());
            assertNotNull(rule, policy.name());
            assertEquals(normalize(policy.promql()), normalize(rule.expr()), policy.name() + " PromQL");
            assertEquals(
                    blankToNull(policy.condition().duration()),
                    blankToNull(rule.forDuration()),
                    policy.name() + " duration");
            assertEquals(policy.severity().name(), rule.labels().get("severity"), policy.name() + " severity");
            assertEquals(policy.owner(), rule.labels().get("owner"), policy.name() + " owner");
            assertEquals(policy.runbookId(), rule.labels().get("runbook_id"), policy.name() + " runbook");
            assertEquals(policy.metricName(), rule.labels().get("kubeoncall_metric"), policy.name() + " metric");
            assertEquals(
                    policy.resourceType().name().toLowerCase(Locale.ROOT),
                    rule.labels().get("resource_type"),
                    policy.name() + " resource type");
            assertEquals(policy.labels().get("team"), rule.labels().get("team"), policy.name() + " team");
            assertTrue(
                    new ClassPathResource("runbooks/" + policy.runbookId() + ".md").exists(),
                    policy.name() + " references a missing runbook");
        }
    }

    @Test
    void everyDeployedAlertShouldResolveToItsActivePolicy() {
        YamlAlarmPolicyRepository repository =
                AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies-node-mvp.yml", AlarmSeverity.P3);
        AlarmPolicyEngine engine = new AlarmPolicyEngine(repository);

        for (AlarmPolicy policy : repository.findAll()) {
            NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                    "alignment-" + policy.id(),
                    "fingerprint-" + policy.id(),
                    policy.name(),
                    "prometheus",
                    policy.severity().name(),
                    policy.severity(),
                    policy.resourceType(),
                    "resource-1",
                    "test",
                    "default",
                    "kubeoncall",
                    policy.metricName(),
                    null,
                    policy.condition().threshold(),
                    null,
                    policy.condition().duration(),
                    policy.labels(),
                    Map.of(),
                    policy.runbookId(),
                    null,
                    Instant.now(),
                    "alignment contract",
                    Map.of());

            var result = engine.evaluate(event);

            assertTrue(result.matched(), policy.name() + " must match an active policy");
            assertEquals(policy.id(), result.policyId(), policy.name());
        }
    }

    @Test
    void activePoliciesShouldCompileAsPrometheusRules() {
        YamlAlarmPolicyRepository repository =
                AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies-node-mvp.yml", AlarmSeverity.P3);

        var compiled = new AlarmPolicyCompiler(repository).compileActive();

        assertEquals(ACTIVE_RULE_COUNT, compiled.ruleCount());
        assertEquals(64, compiled.checksum().length());
    }

    private static List<PrometheusRule> loadPrometheusRules() throws IOException {
        Path rulesPath = List.of(
                        Path.of("..", "deploy", "prometheus", "rules", "kubeoncall.yml"),
                        Path.of("deploy", "prometheus", "rules", "kubeoncall.yml"))
                .stream()
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("deploy/prometheus/rules/kubeoncall.yml not found"));
        PrometheusRuleFile file =
                new ObjectMapper(new YAMLFactory()).readValue(rulesPath.toFile(), PrometheusRuleFile.class);
        return file.groups().stream().flatMap(group -> group.rules().stream()).toList();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrometheusRuleFile(List<PrometheusGroup> groups) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrometheusGroup(String name, List<PrometheusRule> rules) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PrometheusRule(
            String alert, String expr, @JsonProperty("for") String forDuration, Map<String, String> labels) {}
}
