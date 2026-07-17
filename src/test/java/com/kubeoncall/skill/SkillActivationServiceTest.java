package com.kubeoncall.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.memory.TokenBudget;
import com.kubeoncall.service.KubeOnCallMetricsService;

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

    @Test
    void shouldActivateEnabledSkillExplicitlyRequestedByPlanner() {
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);

        SkillActivation activation = service(metricsService)
                .activate(
                        "inspect the current pod state",
                        Map.of("taskType", "QUERY_METRICS", "service", "payment-service"),
                        List.of("payment-oom-triage"));

        assertTrue(activation.active());
        assertEquals(List.of("payment-oom-triage"), activation.skillIds());
        assertTrue(activation.prompt().contains("Payment OOM"));
    }

    @Test
    void shouldRejectRequestedSkillOutsideItsTaskAndDomain() {
        KubeOnCallMetricsService metricsService = mock(KubeOnCallMetricsService.class);

        SkillActivation activation =
                service(metricsService).activate("clean data", Map.of(), List.of("payment-oom-triage"));

        assertFalse(activation.active());
        assertTrue(activation.skillIds().isEmpty());
    }

    private SkillActivationService service(KubeOnCallMetricsService metricsService) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        SkillFrontmatterParser parser = new SkillFrontmatterParser();
        SkillRegistry registry = new SkillRegistry(properties, parser, enabledStateStore());
        registry.load();
        return new SkillActivationService(
                properties, registry, new SkillMatcher(properties), metricsService, new TokenBudget());
    }

    private static SkillStateStore enabledStateStore() {
        SkillStateStore stateStore = mock(SkillStateStore.class);
        org.mockito.Mockito.when(stateStore.disabledIds()).thenReturn(java.util.Set.of());
        return stateStore;
    }
}
