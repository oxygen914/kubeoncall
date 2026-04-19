package com.kubeoncall.agent.planner;

import com.kubeoncall.agent.node.QueryToolNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlannerQueryToolNode extends QueryToolNode {

    private final AgentToolCatalog agentToolCatalog;

    public PlannerQueryToolNode(AgentToolCatalog agentToolCatalog) {
        this.agentToolCatalog = agentToolCatalog;
    }

    @Override
    public String getName() {
        return "plannerQueryToolNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        String request = state.getUserRequest() == null ? "" : state.getUserRequest();
        String target = inferTarget(request);
        List<Map<String, Object>> availableTools = agentToolCatalog.plannerTools().stream()
                .map(this::toToolMap)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", request);
        payload.put("plannerReadOnlyValidated", agentToolCatalog.isPlannerReadOnly());
        payload.put("sop", Map.of(
                "tool", "knowledge.searchSop",
                "documentId", "SOP-GENERAL-001",
                "title", "Standard investigation procedure for " + target,
                "matchedIntent", inferIntent(request)
        ));
        payload.put("topology", Map.of(
                "tool", "topology.getServiceTopology",
                "service", target,
                "upstreams", List.of("gateway-service"),
                "downstreams", List.of("mysql", "redis")
        ));
        payload.put("serviceMetadata", Map.of(
                "tool", "cmdb.getServiceMetadata",
                "service", target,
                "namespace", inferNamespace(request),
                "environment", inferEnvironment(request),
                "criticality", inferCriticality(target),
                "owner", "lab-ops"
        ));
        payload.put("resourceSnapshot", Map.of(
                "tool", "kubernetes.describeResource",
                "resourceName", target,
                "namespace", inferNamespace(request),
                "kind", "Deployment",
                "status", "Healthy"
        ));
        payload.put("activeAlerts", Map.of(
                "tool", "alerts.getActiveAlerts",
                "service", target,
                "count", request.contains("告警") || request.toLowerCase().contains("alert") ? 2 : 0,
                "labels", List.of("service=" + target, "severity=warning")
        ));
        payload.put("metricsContext", Map.of(
                "tool", "prometheus.queryRange",
                "query", "rate(http_requests_total{service=\"" + target + "\"}[5m])",
                "windowMinutes", 10,
                "trend", "stable"
        ));

        state.getContext().put("plannerAvailableTools", availableTools);
        state.getContext().put("plannerKnowledge", payload);
        state.addObservation("Planner queried read-only tools for target=" + target + ", tools="
                + availableTools.stream().map(tool -> String.valueOf(tool.get("name"))).toList());
        return new NodeResult(getName(), NodeStatus.SUCCESS, "Planner queried knowledge sources", payload);
    }

    private Map<String, Object> toToolMap(ToolDefinition tool) {
        return Map.of(
                "name", tool.name(),
                "executorKind", tool.executorKind(),
                "description", tool.description(),
                "readOnly", tool.readOnly(),
                "requiredParameters", tool.requiredParameters(),
                "targetSystems", tool.targetSystems()
        );
    }

    private String inferTarget(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("payment")) {
            return "payment-service";
        }
        if (lower.contains("gateway")) {
            return "gateway-service";
        }
        if (lower.contains("order")) {
            return "order-service";
        }
        return "unknown-service";
    }

    private String inferIntent(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("日志") || lower.contains("log")) {
            return "QUERY_LOGS";
        }
        if (lower.contains("指标") || lower.contains("metric") || lower.contains("cpu")) {
            return "QUERY_METRICS";
        }
        if (lower.contains("重启") || lower.contains("restart")) {
            return "RESTART_SERVICE";
        }
        if (lower.contains("配置") || lower.contains("config") || lower.contains("timeout")) {
            return "PATCH_CONFIG";
        }
        return "GENERAL_DIAGNOSTICS";
    }

    private String inferNamespace(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("prod") || lower.contains("生产")) {
            return "prod";
        }
        if (lower.contains("staging") || lower.contains("预发")) {
            return "staging";
        }
        return "default";
    }

    private String inferEnvironment(String request) {
        String lower = request.toLowerCase();
        if (lower.contains("prod") || lower.contains("生产")) {
            return "production";
        }
        if (lower.contains("staging") || lower.contains("预发")) {
            return "staging";
        }
        return "lab";
    }

    private String inferCriticality(String target) {
        if (target.contains("payment") || target.contains("gateway") || target.contains("order")) {
            return "high";
        }
        return "medium";
    }
}
