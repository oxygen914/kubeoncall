package com.kubeoncall.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.ingest.AlarmNormalizer;
import com.kubeoncall.alarm.policy.AlarmFingerprintService;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.web.dto.AlarmRequest;

class AlarmPolicyEngineTest {

    private final YamlAlarmPolicyRepository repository =
            AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies.yml", AlarmSeverity.P3);
    private final AlarmPolicyEngine engine = new AlarmPolicyEngine(repository);

    @Test
    void cpuBelowThresholdShouldNotTrigger() {
        AlarmEvaluationResult result = engine.evaluate(cpuEvent(69.0));
        assertFalse(result.matched(), "CPU 69 must not trigger any policy");
    }

    @Test
    void cpuAt70PointOneShouldTriggerP1() {
        AlarmEvaluationResult result = engine.evaluate(cpuEvent(70.1));
        assertTrue(result.matched());
        assertEquals(AlarmSeverity.P1, result.finalSeverity());
        assertEquals("host-high-cpu-p1", result.policyId());
    }

    @Test
    void cpuAt85PointOneShouldTriggerP0() {
        AlarmEvaluationResult result = engine.evaluate(cpuEvent(85.1));
        assertTrue(result.matched());
        assertEquals(AlarmSeverity.P0, result.finalSeverity());
        assertEquals("host-high-cpu-p0", result.policyId());
    }

    @Test
    void p0ShouldRemainP0EvenInLabEnv() {
        // env=lab normally downgrades by one level, but a P0 root-cause/value policy stays P0.
        AlarmEvaluationResult result = engine.evaluate(cpuEvent(90.0, Map.of("env", "lab")));
        assertTrue(result.matched());
        assertEquals(AlarmSeverity.P0, result.finalSeverity());
    }

    @Test
    void tier0ShouldUpgradeP1ToP0() {
        AlarmEvaluationResult result = engine.evaluate(cpuEvent(72.0, Map.of("tier", "tier0")));
        assertTrue(result.matched());
        assertEquals(AlarmSeverity.P0, result.finalSeverity(), "tier0 should upgrade P1 to P0");
    }

    @Test
    void policyShouldProvidePromqlAndWorkflowTemplate() {
        AlarmEvaluationResult result = engine.evaluate(cpuEvent(72.0));
        assertTrue(result.matched());
        assertTrue(result.promql().contains("node_cpu_seconds_total"));
        assertEquals("host-resource", result.workflowTemplate());
        assertEquals("runbook-host-cpu-high", result.runbookId());
        assertEquals("2026-07-08", result.matchedPolicy().version());
        assertEquals("2026-07-08", repository.activeVersion());
    }

    @Test
    void diskPolicyShouldTriggerAt85() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id",
                "fp",
                "HostDiskUsageP1",
                "prometheus",
                "warning",
                null,
                AlarmResourceType.NODE,
                "node-a",
                "lab",
                "monitoring",
                "svc",
                "host.disk.usage_percent",
                86.0,
                85.0,
                "%",
                "10m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-host-disk-usage",
                null,
                Instant.now(),
                "disk high",
                Map.of());
        AlarmEvaluationResult result = engine.evaluate(event);
        assertTrue(result.matched());
        assertEquals(AlarmSeverity.P1, result.finalSeverity());
        assertEquals("host-disk-usage-p1", result.policyId());
    }

    @Test
    void kubeNodeNotReadyShouldFireOnMatchWithoutThreshold() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id",
                "fp",
                "KubeNodeNotReadyP0",
                "kubernetes",
                "critical",
                null,
                AlarmResourceType.NODE,
                "node-a",
                "lab",
                null,
                null,
                "kube.node.ready",
                null,
                null,
                null,
                "3m",
                Map.of("team", "infra"),
                Map.of(),
                "runbook-node-notready",
                null,
                Instant.now(),
                "node not ready",
                Map.of());
        AlarmEvaluationResult result = engine.evaluate(event);
        assertTrue(result.matched());
        assertEquals(AlarmSeverity.P0, result.finalSeverity());
        assertEquals("kube-node-not-ready-p0", result.policyId());
    }

    @Test
    void unmatchedEventShouldReturnDefaultSeverity() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id",
                "fp",
                "SomeUnknownAlert",
                "prometheus",
                "warning",
                null,
                AlarmResourceType.SERVICE,
                "svc-x",
                "lab",
                "ns",
                "svc",
                "custom.metric",
                1.0,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                "rb",
                null,
                Instant.now(),
                "s",
                Map.of());
        AlarmEvaluationResult result = engine.evaluate(event);
        assertFalse(result.matched());
        assertEquals(AlarmSeverity.P3, result.finalSeverity());
    }

    @Test
    void autoSilenceShouldBeFalseForAllDefaultPolicies() {
        // Safety invariant: no default policy may default to auto-silence.
        repository
                .findAll()
                .forEach(p -> assertFalse(
                        p.actions().autoSilence(), "policy " + p.id() + " must not auto-silence by default"));
    }

    @Test
    void legacyCriticalPayloadShouldKeepP0AndMatchCpuPolicyWithoutTeamLabel() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AlarmRequest request;
        try (var input = new ClassPathResource("samples/alarm-legacy-format.json").getInputStream()) {
            request = mapper.readValue(input, AlarmRequest.class);
        }
        NormalizedAlarmEvent event = new AlarmNormalizer(new AlarmFingerprintService()).normalize(request);

        AlarmEvaluationResult result = engine.evaluate(event);

        assertTrue(result.matched());
        assertEquals("host-high-cpu-p0", result.policyId());
        assertEquals(AlarmSeverity.P0, result.finalSeverity());
    }

    @Test
    void unmatchedAlarmShouldPreserveMoreSevereUpstreamSeverity() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id",
                "fp",
                "UnknownCriticalAlert",
                "prometheus",
                "critical",
                AlarmSeverity.P0,
                AlarmResourceType.SERVICE,
                "svc-x",
                "prod",
                "ns",
                "svc",
                "custom.metric",
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                Instant.now(),
                "critical unknown",
                Map.of());

        AlarmEvaluationResult result = engine.evaluate(event);

        assertFalse(result.matched());
        assertEquals(AlarmSeverity.P0, result.finalSeverity());
    }

    @Test
    void customAlertNameShouldStillMatchKnownMetricPolicy() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id",
                "fp",
                "CustomCpuSaturation",
                "prometheus",
                "warning",
                AlarmSeverity.P2,
                AlarmResourceType.NODE,
                "node-a",
                "prod",
                "monitoring",
                "infra-exporter",
                "host.cpu.usage_percent",
                72.0,
                null,
                "%",
                "10m",
                Map.of(),
                Map.of(),
                null,
                null,
                Instant.now(),
                "cpu high",
                Map.of());

        AlarmEvaluationResult result = engine.evaluate(event);

        assertTrue(result.matched());
        assertEquals("host-high-cpu-p1", result.policyId());
        assertEquals(AlarmSeverity.P1, result.finalSeverity());
    }

    @Test
    void eventWithoutAlertNameOrMetricShouldNotMatchStatePolicy() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "id",
                "fp",
                null,
                "legacy",
                "critical",
                AlarmSeverity.P0,
                null,
                "node-a",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                Instant.now(),
                "legacy alarm",
                Map.of());

        AlarmEvaluationResult result = engine.evaluate(event);

        assertFalse(result.matched());
        assertEquals(AlarmSeverity.P0, result.finalSeverity());
    }

    private NormalizedAlarmEvent cpuEvent(double currentValue) {
        return cpuEvent(currentValue, Map.of("team", "infra"));
    }

    private NormalizedAlarmEvent cpuEvent(double currentValue, Map<String, String> labels) {
        Map<String, String> mergedLabels = new LinkedHashMap<>();
        mergedLabels.put("team", "infra");
        mergedLabels.putAll(labels);
        return new NormalizedAlarmEvent(
                "id",
                "fp",
                "HostHighCpuUsageP1",
                "prometheus",
                "warning",
                null,
                AlarmResourceType.NODE,
                "node-a",
                "lab",
                "monitoring",
                "infra-exporter",
                "host.cpu.usage_percent",
                currentValue,
                70.0,
                "%",
                "10m",
                mergedLabels,
                Map.of(),
                "runbook-host-cpu-high",
                null,
                Instant.now(),
                "cpu high",
                Map.of());
    }
}
