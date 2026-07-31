package com.kubeoncall.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.RiskLevel;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.sandbox.domain.SandboxRunMode;

class SandboxRoutingPolicyTest {

    @Test
    void keepsOrdinaryLogQueriesOnTheExistingReadOnlyPath() {
        SandboxRoutingPolicy policy = policy(true);

        var decision = policy.decide(
                task(TaskType.QUERY_LOGS), "请查询 sandbox namespace 的错误日志", Map.of("sandboxEvidenceRequired", true));

        assertThat(decision.routed()).isFalse();
    }

    @Test
    void requiresBothDeploymentOptInAndMissingEvidence() {
        assertThat(policy(false)
                        .decide(
                                task(TaskType.RESTART_SERVICE),
                                "在 sandbox 中隔离诊断",
                                Map.of("sandboxEvidenceRequired", true))
                        .routed())
                .isFalse();
        assertThat(policy(true)
                        .decide(task(TaskType.RESTART_SERVICE), "在 sandbox 中隔离诊断", Map.of())
                        .routed())
                .isFalse();
    }

    @Test
    void routesOnlyExplicitCapabilityRequests() {
        SandboxRoutingPolicy policy = policy(true);
        Map<String, Object> context = Map.of("plannerMissingSignals", List.of("runtime_error_context_missing"));

        assertThat(policy.decide(task(TaskType.RESTART_SERVICE), "请在沙箱隔离诊断", context)
                        .mode())
                .isEqualTo(SandboxRunMode.FIXED_DIAGNOSTIC);
        assertThat(policy.decide(task(TaskType.RESTART_SERVICE), "先仿真这个修复方案", context)
                        .routed())
                .isFalse();
        assertThat(policy.decide(task(TaskType.PATCH_CONFIG), "校验 YAML 和 Helm", context)
                        .routed())
                .isFalse();
        assertThat(policy.decide(task(TaskType.EXECUTE_SCRIPT), "运行生成脚本", context)
                        .routed())
                .isFalse();
    }

    private static SandboxRoutingPolicy policy(boolean enabled) {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getSandbox().setAgentAutoRouteEnabled(enabled);
        return new SandboxRoutingPolicy(properties);
    }

    private static Task task(TaskType type) {
        return new Task("task-1", "test", type, RiskLevel.MEDIUM, "api", Map.of(), null);
    }
}
