package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.memory.TokenBudget;

class SkillLoadToolTest {

    @Test
    void shouldLoadOnlyRequestedEnabledSkillWithinTokenBudget() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSkill().setProjectLocation("");
        SkillStateStore stateStore = mock(SkillStateStore.class);
        when(stateStore.disabledIds()).thenReturn(Set.of());
        SkillRegistry registry = new SkillRegistry(properties, new SkillFrontmatterParser(), stateStore);
        registry.reload();
        SkillLoadTool tool = new SkillLoadTool(registry, new TokenBudget());

        Map<String, Object> result = tool.load("payment-oom-triage", 40);

        assertEquals("success", result.get("status"));
        assertEquals("load_skill", result.get("tool"));
        assertTrue((Integer) result.get("tokenCount") <= 40);
        assertEquals("not_found", tool.load("unknown", 40).get("status"));
    }
}
