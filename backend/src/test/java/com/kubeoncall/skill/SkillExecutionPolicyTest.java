package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.task.RiskLevel;

class SkillExecutionPolicyTest {

    @Test
    void shouldRemainUnrestrictedWhenNoSkillIsActive() {
        SkillExecutionPolicy.ToolAccess access =
                SkillExecutionPolicy.toolAccess(Map.of("activatedSkillToolWhitelist", List.of()));

        assertFalse(access.restricted());
        assertTrue(access.allows("kubernetes.rolloutRestart"));
    }

    @Test
    void shouldFailClosedAndSupportExplicitNamespaceWildcardsForActiveSkill() {
        SkillExecutionPolicy.ToolAccess empty = SkillExecutionPolicy.toolAccess(Map.of(
                "activatedSkillIds", List.of("node-triage"),
                "activatedSkillToolWhitelist", List.of()));
        SkillExecutionPolicy.ToolAccess wildcard = SkillExecutionPolicy.toolAccess(Map.of(
                "activatedSkillIds", List.of("node-triage"),
                "activatedSkillToolWhitelist", List.of("kubernetes.*", "prometheus.instantQuery")));

        assertTrue(empty.restricted());
        assertFalse(empty.allows("kubernetes.describeResource"));
        assertTrue(wildcard.allows("kubernetes.describeResource"));
        assertTrue(wildcard.allows("prometheus.instantQuery"));
        assertFalse(wildcard.allows("prometheus.rangeQuery"));
    }

    @Test
    void shouldParseMaxRiskWithoutTreatingInvalidValuesAsAuthority() {
        assertEquals(RiskLevel.LOW, SkillExecutionPolicy.maxRisk(Map.of("activatedSkillMaxRisk", "low")));
        assertNull(SkillExecutionPolicy.maxRisk(Map.of("activatedSkillMaxRisk", "invalid")));
        assertTrue(SkillExecutionPolicy.exceedsMaxRisk(RiskLevel.MEDIUM, RiskLevel.LOW));
        assertFalse(SkillExecutionPolicy.exceedsMaxRisk(RiskLevel.LOW, RiskLevel.LOW));
    }
}
