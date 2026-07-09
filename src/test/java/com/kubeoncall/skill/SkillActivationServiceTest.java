package com.kubeoncall.skill;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.service.KubeOnCallMetricsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkillActivationServiceTest {

    @Test
    void shouldActivatePaymentOomSkill() {
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        SkillActivationService service = service(metricsService);

        SkillActivation activation = service.activate("payment-service pod OOMKilled 了，帮我先排查", Map.of());

        assertTrue(activation.active());
        assertEquals(List.of("payment-oom-triage"), activation.skillIds());
        assertEquals(RiskLevel.MEDIUM, activation.maxRisk());
        assertTrue(activation.toolWhitelist().contains("kubernetes.describeResource"));
        assertTrue(activation.prompt().contains("Verify current state first"));
        verify(metricsService).recordSkillActivation(true, 1);
    }

    @Test
    void shouldNotActivateUnrelatedRequest() {
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);
        SkillActivation activation = service(metricsService).activate("gateway-service latency is high", Map.of());

        assertFalse(activation.active());
        assertTrue(activation.skillIds().isEmpty());
        verify(metricsService).recordSkillActivation(false, 0);
    }

    private SkillActivationService service(KubeOnCallMetricsService metricsService) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        SkillFrontmatterParser parser = new SkillFrontmatterParser();
        SkillRegistry registry = new SkillRegistry(properties, parser);
        registry.load();
        return new SkillActivationService(properties, registry, new SkillMatcher(properties), metricsService);
    }
}
