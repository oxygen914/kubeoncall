package com.kubeoncall.alarm.suppression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class AlarmSuppressionRuleRepositoryTest {

    @Test
    void shouldLoadVersionedProductionRules() {
        AlarmSuppressionRuleRepository repository =
                new AlarmSuppressionRuleRepository(new ClassPathResource("alarm-suppression-rules.yml"));

        AlarmSuppressionRuleRepository.ReloadResult result = repository.reload();

        assertEquals("2026-07-11", result.activeVersion());
        assertEquals(2, result.ruleCount());
        assertEquals(
                "node-not-ready-suppresses-pod", repository.findAll().get(0).id());
    }

    @Test
    void shouldRejectDuplicateRuleIdsWithoutReplacingActiveRules() {
        String yaml = """
                version: v2
                rules:
                  - id: duplicate
                    source: { resourceTypes: [NODE], alertNamePatterns: ['*NodeNotReady*'] }
                    target: { resourceTypes: [POD], alertNamePatterns: [] }
                    correlateBy: [cluster, node]
                    ttlSeconds: 60
                  - id: duplicate
                    source: { resourceTypes: [NODE], alertNamePatterns: ['*NodeNotReady*'] }
                    target: { resourceTypes: [POD], alertNamePatterns: [] }
                    correlateBy: [cluster, node]
                    ttlSeconds: 60
                """;
        AlarmSuppressionRuleRepository repository =
                new AlarmSuppressionRuleRepository(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThrows(IllegalArgumentException.class, repository::reload);
        assertEquals("unloaded", repository.activeVersion());
        assertEquals(0, repository.findAll().size());
    }
}
