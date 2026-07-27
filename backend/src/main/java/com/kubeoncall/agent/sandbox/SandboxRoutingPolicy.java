package com.kubeoncall.agent.sandbox;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.Task;
import com.kubeoncall.sandbox.domain.SandboxRunMode;

/**
 * Conservative Agent-to-Sandbox routing decision.
 *
 * <p>This policy never turns an ordinary read-only query into a Sandbox run. It only returns a
 * route when the deployment opt-in is enabled, the request explicitly asks for an isolated
 * diagnostic/rehearsal action, and the planner has recorded insufficient current evidence. The
 * returned value is a routing intent, not permission to execute a production change.
 */
@Component
public class SandboxRoutingPolicy {

    private final KubeOnCallProperties properties;

    public SandboxRoutingPolicy(KubeOnCallProperties properties) {
        this.properties = properties;
    }

    public Decision decide(Task task, String request, Map<String, Object> context) {
        if (!properties.getSandbox().isAgentAutoRouteEnabled()) {
            return Decision.no("agent auto-route is disabled");
        }
        String text = (request == null ? "" : request).toLowerCase(Locale.ROOT);
        if (task == null || isReadOnlyQuery(task)) {
            return Decision.no("ordinary read-only query remains on its existing tool path");
        }
        if (!evidenceInsufficient(context)) {
            return Decision.no("planner has sufficient evidence without sandbox execution");
        }
        if (mentions(text, "sandbox", "沙箱", "隔离诊断", "isolated diagnostic")) {
            return Decision.route(SandboxRunMode.FIXED_DIAGNOSTIC, "explicit isolated diagnostic request");
        }
        if (mentions(text, "模拟", "仿真", "rehearse", "simulation")) {
            return Decision.no("remediation simulation requires an explicitly submitted redacted resource package");
        }
        if (mentions(text, "校验 yaml", "validate yaml", "helm 校验", "runbook 校验")) {
            return Decision.no("manifest validation requires an explicitly submitted document");
        }
        if (mentions(text, "生成脚本", "generated script", "临时代码", "temporary code")) {
            return Decision.no("generated code requires an explicitly admitted source artifact");
        }
        return Decision.no("request does not explicitly match a sandbox capability");
    }

    private static boolean isReadOnlyQuery(Task task) {
        return task.taskType() != null && task.taskType().name().startsWith("QUERY_");
    }

    private static boolean evidenceInsufficient(Map<String, Object> context) {
        if (context == null) {
            return false;
        }
        Object missing = context.get("plannerMissingSignals");
        if (missing instanceof java.util.Collection<?> signals && !signals.isEmpty()) {
            return true;
        }
        return Boolean.TRUE.equals(context.get("sandboxEvidenceRequired"));
    }

    private static boolean mentions(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public record Decision(SandboxRunMode mode, String reason) {
        static Decision route(SandboxRunMode mode, String reason) {
            return new Decision(mode, reason);
        }

        static Decision no(String reason) {
            return new Decision(null, reason);
        }

        public boolean routed() {
            return mode != null;
        }
    }
}
