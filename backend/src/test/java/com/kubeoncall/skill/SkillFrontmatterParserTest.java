package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SkillFrontmatterParserTest {

    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    @Test
    void shouldParseTypedAlarmSelectors() {
        Skill skill = parser.parse("pod-oom-triage", """
                ---
                id: pod-oom-triage
                name: Pod OOMKilled Triage
                alertNames: [PodOOMKilledP1]
                metricNames: [kube.pod.oom_killed]
                runbookIds: [runbook-pod-oom]
                categories: [K8S-POD]
                resourceTypes: [pod]
                applicableTasks: [QUERY_LOGS, QUERY_METRICS]
                ---
                Verify live OOM evidence.
                """);

        assertEquals(List.of("PodOOMKilledP1"), skill.alertNames());
        assertEquals(List.of("kube.pod.oom_killed"), skill.metricNames());
        assertEquals(List.of("runbook-pod-oom"), skill.runbookIds());
        assertEquals(List.of("k8s-pod"), skill.categories());
        assertEquals(List.of("PodOOMKilledP1"), skill.summary().get("alertNames"));
    }

    @Test
    void oldFrontmatterShouldRemainCompatible() {
        Skill skill = parser.parse("legacy", """
                ---
                id: legacy
                name: Legacy
                triggers: [legacy alert]
                ---
                Legacy body.
                """);

        assertTrue(skill.alertNames().isEmpty());
        assertTrue(skill.metricNames().isEmpty());
        assertTrue(skill.runbookIds().isEmpty());
        assertTrue(skill.categories().isEmpty());
    }
}
