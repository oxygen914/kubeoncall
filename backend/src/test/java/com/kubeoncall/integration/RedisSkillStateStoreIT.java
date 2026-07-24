package com.kubeoncall.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillStateStore;

class RedisSkillStateStoreIT {

    private static final String DISABLED_SKILLS_KEY = "skill:disabled";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(DISABLED_SKILLS_KEY);
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            redisTemplate.delete(DISABLED_SKILLS_KEY);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldPersistAndRefreshDisabledSkillsThroughRealRedis() {
        SkillStateStore store = new SkillStateStore(
                redisTemplate, new KubeOnCallProperties(), org.mockito.Mockito.mock(KubeOnCallMetricsService.class));

        store.disable("payment-oom-triage");

        assertTrue(store.disabledIds().contains("payment-oom-triage"));
        assertFalse(store.isEnabled("payment-oom-triage"));

        store.enable("payment-oom-triage");

        assertFalse(store.disabledIds().contains("payment-oom-triage"));
        assertTrue(store.isEnabled("payment-oom-triage"));
    }
}
