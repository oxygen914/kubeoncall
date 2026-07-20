package com.kubeoncall.alarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;

class YamlAlarmPolicyRepositoryTest {

    @Test
    void shouldLoadDefaultClasspathPolicies() {
        YamlAlarmPolicyRepository repo =
                AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies.yml", AlarmSeverity.P3);
        assertTrue(repo.findAll().size() >= 11, "default policies should cover the first batch");
        assertTrue(repo.findByName("HostHighCpuUsageP1").isPresent());
        assertTrue(repo.findByName("HostHighCpuUsageP0").isPresent());
        assertTrue(repo.findByName("KubeNodeNotReadyP0").isPresent());
        assertTrue(repo.findByName("PodOOMKilledP1").isPresent());
        assertTrue(repo.findById("host-high-cpu-p0").isPresent());
        var cpuPolicy = repo.findById("host-high-cpu-p1").orElseThrow();
        assertTrue(cpuPolicy.condition().matchLabels().isEmpty(), "routing labels must not become match requirements");
        assertEquals("infra", cpuPolicy.labels().get("team"));
        assertEquals("runbook", cpuPolicy.ragFilters().get("document_type"));
        assertEquals("host", cpuPolicy.ragFilters().get("category"));
        var oomPolicy = repo.findById("pod-oom-killed-p1").orElseThrow();
        assertEquals("k8s-pod", oomPolicy.ragFilters().get("category"));
    }

    @Test
    void shouldLoadOnlyNodeMonitoringMvpPolicies() {
        YamlAlarmPolicyRepository repo =
                AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies-node-mvp.yml", AlarmSeverity.P3);

        assertEquals(5, repo.findAll().size());
        assertTrue(repo.findByName("NodeDown").isPresent());
        assertTrue(repo.findByName("NodeCPUHigh").isPresent());
        assertTrue(repo.findByName("NodeMemoryLow").isPresent());
        assertTrue(repo.findByName("NodeDiskHigh").isPresent());
        assertTrue(repo.findByName("NodeInodeHigh").isPresent());
    }

    @Test
    void shouldRejectDuplicatePolicyIds() throws IOException {
        Path tmp = Files.createTempFile("policies-dup", ".yml");
        Files.writeString(tmp, """
                version: test
                policies:
                  - id: dup
                    name: A
                    severity: P1
                    runbookId: rb-a
                  - id: dup
                    name: B
                    severity: P1
                    runbookId: rb-b
                """);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> repository(tmp.toString(), true, AlarmSeverity.P3)
                        .load());
        assertTrue(ex.getMessage().contains("Duplicate alarm policy id"));
    }

    @Test
    void shouldRejectPolicyWithoutRunbook() throws IOException {
        Path tmp = Files.createTempFile("policies-norb", ".yml");
        Files.writeString(tmp, """
                version: test
                policies:
                  - id: no-rb
                    name: NoRunbook
                    severity: P1
                """);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> repository(tmp.toString(), true, AlarmSeverity.P3)
                        .load());
        assertTrue(ex.getMessage().contains("runbookId"));
    }

    @Test
    void shouldRejectInvalidSeverity() throws IOException {
        Path tmp = Files.createTempFile("policies-sev", ".yml");
        Files.writeString(tmp, """
                version: test
                policies:
                  - id: bad-sev
                    name: BadSev
                    severity: PX
                    runbookId: rb
                """);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> repository(tmp.toString(), true, AlarmSeverity.P3)
                        .load());
        assertTrue(ex.getMessage().contains("invalid severity"));
    }

    @Test
    void disabledRepositoryShouldLoadNoPolicies() {
        YamlAlarmPolicyRepository repo = repository("classpath:alarm-policies.yml", false, AlarmSeverity.P3);
        repo.load();
        assertEquals(0, repo.findAll().size());
    }

    private static YamlAlarmPolicyRepository repository(
            String policyLocation, boolean enabled, AlarmSeverity defaultSeverity) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAlarm().setPolicyLocation(policyLocation);
        properties.getAlarm().setEnabled(enabled);
        properties.getAlarm().setDefaultSeverity(defaultSeverity.name());
        return new YamlAlarmPolicyRepository(properties);
    }
}
