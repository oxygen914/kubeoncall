package com.kubeoncall.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.GraphStatus;
import com.kubeoncall.domain.task.PlannerSummary;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskPlan;
import com.kubeoncall.domain.task.TaskType;

class ResponseComposerTest {

    private final ResponseComposer composer = new ResponseComposer();

    @Test
    void composesSuccessfulMetricsResultAsChineseConversation() {
        GraphState state = state(GraphStatus.SUCCESS);
        state.getContext().put("executorResult", Map.of("status", "success"));
        state.getContext()
                .put(
                        "plannerSummary",
                        new PlannerSummary(
                                state.getUserRequest(),
                                "diagnose-cpu-usage",
                                "LOW",
                                "current-scope",
                                "request",
                                Map.of(),
                                List.of("node_cpu_seconds_total", "process_cpu_breakdown"),
                                "METRIC@prometheus=FORBIDDEN: NAMESPACE_NOT_ALLOWED",
                                List.of("prometheus.queryRange"),
                                Map.of(),
                                "REAL_MODEL",
                                false,
                                null,
                                "openai-compatible",
                                "qwen-plus",
                                Map.of()));

        String answer = composer.compose(state);

        assertThat(answer)
                .contains("研判已完成")
                .contains("3/3 Ready")
                .contains("平均 CPU 16.59%")
                .contains("证据可信度：低")
                .contains("CPU 分项时序")
                .contains("跨 Namespace 指标")
                .contains("仅执行只读检查")
                .doesNotContain("executionId=")
                .doesNotContain("planSummary=")
                .doesNotContain("toolSummary=")
                .doesNotContain("auditSummary=");
    }

    @Test
    void explainsFailedExecutionWithoutExposingInternalReport() {
        GraphState state = state(GraphStatus.FAILED);

        assertThat(composer.compose(state))
                .contains("本次研判未能完整完成")
                .contains("查看失败节点")
                .doesNotContain("executionId=");
    }

    private GraphState state(GraphStatus status) {
        GraphState state = new GraphState();
        state.setExecutionId("exe_test");
        state.setStatus(status);
        state.setUserRequest("请分析当前状态；已有证据：3/3 Ready，平均 CPU 16.59%");
        Task task = new Task(
                "task_test",
                "Query current metrics",
                TaskType.QUERY_METRICS,
                RiskLevel.LOW,
                "current-scope",
                Map.of("namespace", "default"),
                null);
        state.setTaskPlan(new TaskPlan("exe_test", state.getUserRequest(), List.of(task), Instant.now(), false));
        state.setCurrentTask(task);
        return state;
    }
}
