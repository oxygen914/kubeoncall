package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.kubeoncall.common.config.DataMigrationProperties;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;

class SkillStateStoreTest {

    @Test
    void shouldPersistDisableAndEnableState() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("skill:disabled")).thenReturn(Set.of("skill-a"));
        SkillStateStore store =
                new SkillStateStore(redisTemplate, new KubeOnCallProperties(), mock(KubeOnCallMetricsService.class));

        assertFalse(store.isEnabled("skill-a"));
        store.disable("skill-b");
        verify(setOperations).add("skill:disabled", "skill-b");
        store.enable("skill-b");
        verify(setOperations).remove("skill:disabled", "skill-b");
        assertTrue(store.isEnabled("skill-b"));
    }

    @Test
    void legacyWriteDisabledSkipsRedisWriteAndRecordsMetric() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getDataMigration().setSkillState(new DataMigrationProperties.DomainRetirement("REDIS", true));
        KubeOnCallMetricsService metrics = mock(KubeOnCallMetricsService.class);
        SkillStateStore store = new SkillStateStore(redisTemplate, properties, metrics);

        store.disable("skill-b");

        verify(setOperations, never()).add("skill:disabled", "skill-b");
        verify(metrics).recordLegacyWriteSkipped("skill-state");
    }

    @Test
    void shouldPreserveDisabledStateAcrossPaymentOomSkillRename() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("skill:disabled")).thenReturn(Set.of("payment-oom-triage"), Set.of());
        SkillStateStore store =
                new SkillStateStore(redisTemplate, new KubeOnCallProperties(), mock(KubeOnCallMetricsService.class));

        assertFalse(store.isEnabled("pod-oom-triage"));
        store.enable("pod-oom-triage");

        verify(setOperations).remove("skill:disabled", "pod-oom-triage", "payment-oom-triage");
        assertTrue(store.isEnabled("pod-oom-triage"));
    }
}
