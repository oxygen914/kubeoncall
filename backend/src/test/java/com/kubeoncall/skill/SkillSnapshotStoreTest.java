package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.domain.task.RiskLevel;

class SkillSnapshotStoreTest {

    @Test
    void shouldPersistAndRestoreSkillBodies() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        ObjectMapper objectMapper = new ObjectMapper();
        Skill skill = skill();
        when(values.get("skill:snapshot:v1")).thenReturn(objectMapper.writeValueAsString(List.of(skill)));
        SkillSnapshotStore store = new SkillSnapshotStore(redisTemplate, objectMapper);

        store.save(List.of(skill));
        List<Skill> restored = store.load();

        verify(values).set(eq("skill:snapshot:v1"), eq(objectMapper.writeValueAsString(List.of(skill))));
        assertEquals("full runbook body", restored.get(0).body());
        assertEquals("pod-oom-triage", restored.get(0).id());
    }

    private Skill skill() {
        return new Skill(
                "pod-oom-triage",
                "Pod OOMKilled Triage",
                "v1",
                SkillSource.PROJECT,
                "file:skills/pod-oom-triage/SKILL.md",
                "triage Pod OOM",
                List.of("oom"),
                List.of(),
                List.of("Pod"),
                RiskLevel.LOW,
                List.of("kubernetes.describeResource"),
                "full runbook body",
                Map.of("owner", "platform"));
    }
}
