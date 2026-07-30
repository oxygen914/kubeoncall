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
import com.kubeoncall.evidence.AiConclusion;
import com.kubeoncall.evidence.ConclusionFactory;
import com.kubeoncall.evidence.ConfidenceAssessment;
import com.kubeoncall.evidence.EvidenceClaimGrounder;
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
                conclusionFactory,
                new EvidenceClaimGrounder());
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

    @Test
    void explicitReadOnlyConstraintCannotBecomeAMutationOrASecondTask() {
        PlannerLlmService llmService = mock(PlannerLlmService.class);
        ConclusionFactory conclusionFactory = mock(ConclusionFactory.class);
        PlannerLlmDecision unsafeDecision = new PlannerLlmDecision(
                "EXECUTE_SCRIPT",
                "HIGH",
                "adapter-abc",
                "llm",
                TaskType.EXECUTE_SCRIPT,
                RiskLevel.CRITICAL,
                Map.of("scriptName", "diagnose"),
                List.of(),
                List.of(),
                "Execute diagnostic script");
        when(llmService.planWithStatus(any(), any()))
                .thenReturn(PlannerLlmResult.success(
                        unsafeDecision, PlannerMode.REAL_MODEL, "aliyun-dashscope", "qwen-plus", 10, Map.of()));
        when(llmService.activateRequestedSkills(any(), any(), any())).thenReturn(SkillActivation.empty());
        when(conclusionFactory.create(any(), any(), any()))
                .thenReturn(new AiConclusion(
                        "con_1",
                        "exe_1",
                        "read-only diagnosis",
                        "P4",
                        "PARTIALLY_SUPPORTED",
                        List.of(),
                        List.of(),
                        new ConfidenceAssessment(0.2, "LOW", Map.of()),
                        Map.of("mode", "REAL_MODEL"),
                        null));
        PlannerThinkNode node = new PlannerThinkNode(
                llmService,
                new PlannerContextAssembler(),
                new PlannerRuleEngine(new PlannerParameterResolver(), new PlannerTaskFactory()),
                conclusionFactory,
                new EvidenceClaimGrounder());
        GraphState state = new GraphState();
        state.setExecutionId("exe_1");
        state.setUserRequest("查看 kubeoncall-system 命名空间中 Pod adapter-abc 的日志；禁止执行任何变更");

        NodeResult result = node.execute(state);

        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(state.getTaskPlan().tasks()).hasSize(1);
        assertThat(state.getCurrentTask().taskType()).isEqualTo(TaskType.QUERY_LOGS);
        assertThat(state.getCurrentTask().riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(state.getContext().get("plannerSource")).isEqualTo("llm+read_only_guard");
    }
}
