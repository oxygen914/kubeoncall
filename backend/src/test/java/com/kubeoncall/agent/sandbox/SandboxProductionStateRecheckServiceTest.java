package com.kubeoncall.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

class SandboxProductionStateRecheckServiceTest {

    @Test
    void usesOnlyFixedReadOnlyWorkloadDescriptionAndStoresSummaryNotRawResponse() {
        ToolExecutor kubernetes = mock(ToolExecutor.class);
        when(kubernetes.getExecutorKind()).thenReturn("kubernetes");
        when(kubernetes.supportedTools()).thenReturn(List.of(tool(true)));
        when(kubernetes.execute(eq("describeWorkload"), anyMap()))
                .thenReturn(Map.of("httpStatus", 200, "status", "ok", "rawSecretLikeData", "never-persisted"));
        GraphState state = state();

        SandboxProductionStateRecheckService.Recheck result =
                new SandboxProductionStateRecheckService(List.of(kubernetes)).recheck(state);

        assertThat(result.current()).isTrue();
        assertThat(state.getContext().get("productionRecheck"))
                .isEqualTo(Map.ofEntries(
                        Map.entry("status", "CURRENT"),
                        Map.entry("tool", "kubernetes.describeWorkload"),
                        Map.entry("httpStatus", 200),
                        Map.entry(
                                "observedAt",
                                ((Map<?, ?>) state.getContext().get("productionRecheck")).get("observedAt"))));
        verify(kubernetes).execute(eq("describeWorkload"), anyMap());
    }

    @Test
    void refusesToRecheckWhenOnlyAMutatingDefinitionIsAvailable() {
        ToolExecutor kubernetes = mock(ToolExecutor.class);
        when(kubernetes.getExecutorKind()).thenReturn("kubernetes");
        when(kubernetes.supportedTools()).thenReturn(List.of(tool(false)));
        GraphState state = state();

        SandboxProductionStateRecheckService.Recheck result =
                new SandboxProductionStateRecheckService(List.of(kubernetes)).recheck(state);

        assertThat(result.current()).isFalse();
        assertThat(result.summary()).containsEntry("status", "UNAVAILABLE");
    }

    private static ToolDefinition tool(boolean readOnly) {
        return new ToolDefinition(
                "kubernetes.describeWorkload",
                "kubernetes",
                "describe",
                readOnly,
                !readOnly,
                List.of(TaskType.RESTART_SERVICE),
                List.of("namespace"),
                List.of("k8s-api"));
    }

    private static GraphState state() {
        GraphState state = new GraphState();
        state.setCurrentTask(new Task(
                "task-123",
                "restart",
                TaskType.RESTART_SERVICE,
                RiskLevel.HIGH,
                "api",
                Map.of("namespace", "default"),
                null));
        return state;
    }
}
