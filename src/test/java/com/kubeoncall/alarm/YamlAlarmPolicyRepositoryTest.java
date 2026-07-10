package com.kubeoncall.alarm;

import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlAlarmPolicyRepositoryTest {

    @Test
    void shouldLoadDefaultClasspathPolicies() {
        YamlAlarmPolicyRepository repo = YamlAlarmPolicyRepository.loadFromClasspath("alarm-policies.yml", AlarmSeverity.P3);
        assertTrue(repo.findAll().size() >= 11, "default policies should cover the first batch");
        assertTrue(repo.findByName("HostHighCpuUsageP1").isPresent());
        assertTrue(repo.findByName("HostHighCpuUsageP0").isPresent());
        assertTrue(repo.findByName("KubeNodeNotReadyP0").isPresent());
        assertTrue(repo.findByName("PodOOMKilledP1").isPresent());
        assertTrue(repo.findById("host-high-cpu-p0").isPresent());
        var cpuPolicy = repo.findById("host-high-cpu-p1").orElseThrow();
        assertTrue(cpuPolicy.condition().matchLabels().isEmpty(), "routing labels must not become match requirements");
        assertEquals("infra", cpuPolicy.labels().get("team"));
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
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new YamlAlarmPolicyRepository(tmp.toString(), true, AlarmSeverity.P3).load());
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
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new YamlAlarmPolicyRepository(tmp.toString(), true, AlarmSeverity.P3).load());
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
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new YamlAlarmPolicyRepository(tmp.toString(), true, AlarmSeverity.P3).load());
        assertTrue(ex.getMessage().contains("invalid severity"));
    }

    @Test
    void disabledRepositoryShouldLoadNoPolicies() {
        YamlAlarmPolicyRepository repo = new YamlAlarmPolicyRepository("classpath:alarm-policies.yml", false, AlarmSeverity.P3);
        repo.load();
        assertEquals(0, repo.findAll().size());
    }
}
