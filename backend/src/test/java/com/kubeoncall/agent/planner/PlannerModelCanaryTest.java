package com.kubeoncall.agent.planner;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;

class PlannerModelCanaryTest {

    @Test
    void disabledCanaryDoesNotCallTheProvider() {
        PlannerLlmService planner = mock(PlannerLlmService.class);
        KubeOnCallProperties properties = properties(false, true);

        new PlannerModelCanary(planner, properties).run(null);

        verifyNoInteractions(planner);
    }

    @Test
    void modelBackedStructuredResultPasses() {
        PlannerLlmService planner = mock(PlannerLlmService.class);
        KubeOnCallProperties properties = properties(true, true);
        PlannerLlmDecision decision = new PlannerLlmDecision(
                "canary",
                "HIGH",
                "kubeoncall-planner-canary",
                "REQUEST",
                TaskType.QUERY_METRICS,
                RiskLevel.LOW,
                Map.of(),
                List.of(),
                List.of(),
                "Planner canary passed");
        when(planner.planWithStatus(anyString(), anyMap()))
                .thenReturn(PlannerLlmResult.success(
                        decision, PlannerMode.REAL_MODEL, "aliyun-dashscope", "qwen-plus", 25, Map.of()));

        assertThatCode(() -> new PlannerModelCanary(planner, properties).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void degradedResultFailsClosedWithoutProviderResponseDetails() {
        PlannerLlmService planner = mock(PlannerLlmService.class);
        KubeOnCallProperties properties = properties(true, true);
        when(planner.planWithStatus(anyString(), anyMap()))
                .thenReturn(PlannerLlmResult.degraded(
                        PlannerMode.RULE_FALLBACK,
                        PlannerDegradedReason.PROVIDER_ERROR,
                        "aliyun-dashscope",
                        "qwen-plus",
                        25));

        assertThatThrownBy(() -> new PlannerModelCanary(planner, properties).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROVIDER_ERROR")
                .hasMessageNotContaining("response body");
    }

    private static KubeOnCallProperties properties(boolean enabled, boolean failFast) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setPlannerMode("REAL_MODEL");
        properties.getAiOperations().setPlannerCanaryEnabled(enabled);
        properties.getAiOperations().setPlannerCanaryFailFast(failFast);
        return properties;
    }
}
