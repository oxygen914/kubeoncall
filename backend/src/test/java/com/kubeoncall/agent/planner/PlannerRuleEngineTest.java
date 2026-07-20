package com.kubeoncall.agent.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;

class PlannerRuleEngineTest {

    private final PlannerRuleEngine ruleEngine =
            new PlannerRuleEngine(new PlannerParameterResolver(), new PlannerTaskFactory());

    @Test
    void shouldInferProductionRestartAsHighRiskWithMetadataParameters() {
        Map<String, Object> knowledge =
                Map.of("serviceMetadata", Map.of("namespace", "payments", "environment", "production"));
        String request = "restart payment-service";

        String intent = ruleEngine.inferIntent(request);
        TaskType taskType = ruleEngine.mapIntentToTaskType(intent);
        String target = ruleEngine.inferTarget(request);
        Map<String, Object> parameters = ruleEngine.inferParameters(request, taskType, target, knowledge);

        assertEquals("RESTART_SERVICE", intent);
        assertEquals("payment-service", target);
        assertEquals("payments", parameters.get("namespace"));
        assertEquals(RiskLevel.HIGH, ruleEngine.inferRiskLevel(intent, taskType, target, parameters, knowledge));
    }

    @Test
    void shouldRequestAdditionalContextForScaleWithoutReplicaSignal() {
        String request = "扩容 payment-service";
        TaskType taskType = ruleEngine.mapIntentToTaskType(ruleEngine.inferIntent(request));
        Map<String, Object> parameters = ruleEngine.inferParameters(request, taskType, "payment-service", Map.of());
        List<String> missingSignals = ruleEngine.identifyMissingSignals(request, taskType, parameters, Map.of());

        assertTrue(missingSignals.contains("target_replica_count_not_specified"));
        assertTrue(ruleEngine.shouldRetryForMissingSignals(taskType, missingSignals, 0));
        assertFalse(ruleEngine.shouldRetryForMissingSignals(taskType, missingSignals, 1));
        assertEquals(
                List.of("restart payment-service", "查询 payment-service 日志"),
                ruleEngine.splitTaskRequests("restart payment-service 然后 查询 payment-service 日志"));
    }
}
