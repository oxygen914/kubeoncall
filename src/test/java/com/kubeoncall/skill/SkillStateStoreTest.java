package com.kubeoncall.skill;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillStateStoreTest {

    @Test
    void shouldPersistDisableAndEnableState() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("skill:disabled")).thenReturn(Set.of("skill-a"));
        SkillStateStore store = new SkillStateStore(redisTemplate);

        assertFalse(store.isEnabled("skill-a"));
        store.disable("skill-b");
        verify(setOperations).add("skill:disabled", "skill-b");
        store.enable("skill-b");
        verify(setOperations).remove("skill:disabled", "skill-b");
        assertTrue(store.isEnabled("skill-b"));
    }
}
