package com.kubeoncall.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.evidence.ConclusionFactory;
import com.kubeoncall.skill.SkillActivation;

class PlannerThinkNodeTest {

    @Test
    void rejectsASecondMutationThatWasNotIndividuallyValidatedByTheModel() {
        PlannerLlmService llmService = mock(PlannerLlmService.class);
        ConclusionFactory conclusionFactory = mock(ConclusionFactory.class);
        PlannerLlmDecision decision = new PlannerLlmDecision(
                "RESTART_SERVICE",
                "HIGH",
                "payment-service",
                "llm",
                TaskType.RESTART_SERVICE,
                RiskLevel.HIGH,
                Map.of("namespace", "payments"),
                List.of(),
                List.of(),
                "Restart payment-service");
        when(llmService.planWithStatus(any(), any()))
                .thenReturn(PlannerLlmResult.success(
                        decision, PlannerMode.REAL_MODEL, "openai-compatible", "test-model", 10, Map.of()));
        when(llmService.activateRequestedSkills(any(), any(), any())).thenReturn(SkillActivation.empty());
        PlannerThinkNode node = new PlannerThinkNode(
                llmService,
                new PlannerContextAssembler(),
                new PlannerRuleEngine(new PlannerParameterResolver(), new PlannerTaskFactory()),
                conclusionFactory);
        GraphState state = new GraphState();
        state.setUserRequest("restart payment-service 然后扩容 order-service 到 3 副本");

        NodeResult result = node.execute(state);

        assertThat(result.status()).isEqualTo(NodeStatus.FAILURE);
        assertThat(result.payload())
                .containsEntry("reason", "COMPOUND_MUTATION_REQUIRES_SEPARATE_EXECUTION")
                .containsEntry("taskType", TaskType.SCALE_WORKLOAD.name());
        assertThat(state.getTaskPlan()).isNull();
        assertThat(state.getContext().get("plannerRejectedCompoundMutation")).isEqualTo("扩容 order-service 到 3 副本");
        verify(conclusionFactory, never()).create(any(), any(), any());
    }
}
