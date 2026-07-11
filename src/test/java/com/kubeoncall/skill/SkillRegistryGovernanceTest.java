package com.kubeoncall.skill;

import com.kubeoncall.common.config.KubeOnCallProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRegistryGovernanceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLetProjectSkillOverrideBuiltinAndIsolateBrokenFiles() throws Exception {
        Path overrideDir = Files.createDirectories(tempDir.resolve("payment-oom-triage"));
        Files.writeString(overrideDir.resolve("SKILL.md"), """
                ---
                id: payment-oom-triage
                name: Project Payment OOM
                version: v2
                description: project override
                triggers: [OOMKilled]
                toolWhitelist: [kubernetes.describeResource]
                ---
                Project-specific procedure.
                """);
        Path brokenDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(brokenDir.resolve("SKILL.md"), """
                ---
                id: [broken
                ---
                invalid
                """);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSkill().setProjectLocation(
                "file:" + tempDir.toAbsolutePath().toString().replace('\\', '/') + "/**/SKILL.md");
        SkillRegistry registry = new SkillRegistry(properties, new SkillFrontmatterParser());

        SkillRegistry.ReloadResult result = registry.reload();
        Skill skill = registry.findById("payment-oom-triage").orElseThrow();

        assertEquals(SkillSource.PROJECT, skill.source());
        assertEquals("v2", skill.version());
        assertTrue(skill.body().contains("Project-specific"));
        assertEquals(1, result.projectOverrides());
        assertEquals(1, result.errors().size());
    }

    @Test
    void shouldExcludeDisabledSkillFromActivationIndex() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSkill().setProjectLocation("");
        SkillStateStore stateStore = mock(SkillStateStore.class);
        when(stateStore.disabledIds()).thenReturn(Set.of("payment-oom-triage"));
        SkillRegistry registry = new SkillRegistry(properties, new SkillFrontmatterParser(), stateStore);
        registry.reload();

        assertTrue(registry.all().stream().noneMatch(skill -> "payment-oom-triage".equals(skill.id())));
        Map<String, Object> payment = registry.index().stream()
                .filter(item -> "payment-oom-triage".equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertFalse((Boolean) payment.get("enabled"));

        registry.enable("payment-oom-triage");
        verify(stateStore).enable("payment-oom-triage");
    }
}
