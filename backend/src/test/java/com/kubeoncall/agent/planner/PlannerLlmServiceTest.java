package com.kubeoncall.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillActivationService;
import com.kubeoncall.skill.SkillLoadTool;

class PlannerLlmServiceTest {

    @Test
    void configuredRuleFallbackIsExplicitAndDoesNotResolveAClient() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setPlannerMode("RULE_FALLBACK");
        ObjectProvider<ChatClient> provider = provider(null);
        PlannerLlmService service = service(properties, provider);

        PlannerLlmResult result = service.planWithStatus("inspect pod", Map.of());

        assertThat(result.decision()).isNull();
        assertThat(result.mode()).isEqualTo(PlannerMode.RULE_FALLBACK);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradedReason()).isEqualTo(PlannerDegradedReason.RULE_MODE_CONFIGURED);
        verifyNoInteractions(provider);
    }

    @Test
    void missingRealModelClientDowngradesToReadOnlyWithAStableReason() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setPlannerMode("REAL_MODEL");
        PlannerLlmService service = service(properties, provider(null));

        PlannerLlmResult result = service.planWithStatus("inspect pod", Map.of());

        assertThat(result.mode()).isEqualTo(PlannerMode.RULE_FALLBACK);
        assertThat(result.degradedReason()).isEqualTo(PlannerDegradedReason.CLIENT_MISSING);
        assertThat(result.mode().mutationCandidateAllowed()).isFalse();
    }

    @Test
    void invalidAndSimulationModesNeverBecomeRealModelResults() {
        KubeOnCallProperties invalid = new KubeOnCallProperties();
        invalid.getAiOperations().setPlannerMode("not-a-mode");
        PlannerLlmResult invalidResult = service(invalid, provider(null)).planWithStatus("inspect pod", Map.of());
        assertThat(invalidResult.mode()).isEqualTo(PlannerMode.UNAVAILABLE);
        assertThat(invalidResult.degradedReason()).isEqualTo(PlannerDegradedReason.INVALID_MODE);

        KubeOnCallProperties simulation = new KubeOnCallProperties();
        simulation.getAiOperations().setPlannerMode("SIMULATION");
        PlannerLlmResult simulationResult = service(simulation, provider(null)).planWithStatus("inspect pod", Map.of());
        assertThat(simulationResult.mode()).isEqualTo(PlannerMode.SIMULATION);
        assertThat(simulationResult.degradedReason()).isEqualTo(PlannerDegradedReason.SIMULATION_CONFIGURED);
        assertThat(simulationResult.mode().mutationCandidateAllowed()).isFalse();
    }

    @Test
    void redactsCredentialsBeforeBuildingTheModelPrompt() throws Exception {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        PlannerLlmService service = service(properties, provider(null));

        String prompt = service.buildUserPrompt(
                "inspect pod with Authorization: Bearer secret-token-value",
                Map.of(
                        "loki",
                        Map.of(
                                "apiToken",
                                "internal-token-value",
                                "lines",
                                java.util.List.of("password=database-secret"))));

        assertThat(prompt)
                .contains("[REDACTED]")
                .doesNotContain("secret-token-value", "internal-token-value", "database-secret");
    }

    @Test
    void normalizesBoundedNumericConfidenceFromOpenAiCompatibleProviders() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        PlannerLlmService service = service(properties, provider(null));

        PlannerLlmDecision decision = service.parseDecision("""
                {
                  "intent": "inspect",
                  "confidence": 0.95,
                  "target": "pod/example",
                  "targetSource": "request",
                  "taskType": "QUERY_METRICS",
                  "riskLevel": "LOW",
                  "parameters": {},
                  "missingSignals": [],
                  "requestedSkills": [],
                  "summary": "read-only inspection"
                }
                """);

        assertThat(decision.confidence()).isEqualTo("HIGH");
    }

    private static PlannerLlmService service(KubeOnCallProperties properties, ObjectProvider<ChatClient> provider) {
        return new PlannerLlmService(
                provider,
                new ObjectMapper(),
                properties,
                mock(SkillActivationService.class),
                mock(SkillLoadTool.class),
                mock(KubeOnCallMetricsService.class));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatClient> provider(ChatClient value) {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
