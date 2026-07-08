package com.kubeoncall.agent.planner;

import com.kubeoncall.agent.node.QueryToolNode;
import com.kubeoncall.domain.graph.GraphState;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.tool.AgentToolCatalog;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.mcp.McpClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PlannerQueryToolNode extends QueryToolNode {

    private final AgentToolCatalog agentToolCatalog;
    private final McpClient mcpClient;

    public PlannerQueryToolNode(AgentToolCatalog agentToolCatalog, McpClient mcpClient) {
        this.agentToolCatalog = agentToolCatalog;
        this.mcpClient = mcpClient;
    }

    @Override
    public String getName() {
        return "plannerQueryToolNode";
    }

    @Override
    public NodeResult execute(GraphState state) {
        String request = planningRequest(state);
        String target = inferTarget(request);
        List<Map<String, Object>> availableTools = agentToolCatalog.plannerTools().stream()
                .map(this::toToolMap)
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", request);
        payload.put("plannerReadOnlyValidated", agentToolCatalog.isPlannerReadOnly());

        payload.put("sop", queryWithFallback(
                "knowledge.searchSop",
                Map.of("query", request),
                Map.of(
                        "tool", "knowledge.searchSop",
                        "documentId", "SOP-GENERAL-001",
                        "title", "Standard investigation procedure for " + target,
                        "matchedIntent", inferIntent(request)
                )
        ));

        payload.put("topology", queryWithFallback(
                "topology.getServiceTopology",
                Map.of("serviceName", target),
                Map.of(
                        "tool", "topology.getServiceTopology",
                        "service", target,
                        "upstreams", List.of("gateway-service"),
                        "downstreams", List.of("mysql", "redis")
                )
        ));

        payload.put("serviceMetadata", queryWithFallback(
                "cmdb.getServiceMetadata",
                Map.of("serviceName", target),
                Map.of(
                        "tool", "cmdb.getServiceMetadata",
                        "service", target,
                        "namespace", inferNamespace(request),
                        "environment", inferEnvironment(request),
                        "criticality", inferCriticality(target),
                        "owner", "lab-ops"
                )
        ));

        payload.put("resourceSnapshot", queryWithFallback(
                "kubernetes.describeResource",
                Map.of("resourceName", target, "namespace", inferNamespace(request)),
                Map.of(
                        "tool", "kubernetes.describeResource",
                        "resourceName", target,
                        "namespace", inferNamespace(request),
                        "kind", "Deployment",
                        "status", "Healthy"
                )
        ));

        payload.put("activeAlerts", queryWithFallback(
                "alerts.getActiveAlerts",
                Map.of("serviceName", target),
                Map.of(
                        "tool", "alerts.getActiveAlerts",
                        "service", target,
                        "count", request.contains("告警") || request.toLowerCase().contains("alert") ? 2 : 0,
                        "labels", List.of("service=" + target, "severity=warning")
                )
        ));

        payload.put("metricsContext", queryWithFallback(
                "prometheus.queryRange",
                Map.of("query", "rate(http_requests_total{service=\"" + target + "\"}[5m])", "windowMinutes", 10),
                Map.of(
                        "tool", "prometheus.queryRange",
                        "query", "rate(http_requests_total{service=\"" + target + "\"}[5m])",
                        "windowMinutes", 10,
                        "trend", "stable"
                )
        ));

        List<String> missingSignals = readMissingSignals(state);
        if (!missingSignals.isEmpty()) {
            Map<String, Object> supplementalSignals = new LinkedHashMap<>();
            if (missingSignals.contains("target_replica_count_not_specified")) {
                Map<String, Object> scaleHint = queryWithFallback(
                        "topology.getServiceTopology",
                        Map.of("serviceName", target, "mode", "capacity_hint"),
                        Map.of("tool", "topology.getServiceTopology", "recommendedReplicas", 3)
                );
                Object replicas = scaleHint.get("recommendedReplicas");
                supplementalSignals.put("recommendedReplicas", replicas instanceof Number number ? number.intValue() : 3);
                supplementalSignals.put("scaleHintSource", String.valueOf(scaleHint.getOrDefault("tool", "topology.getServiceTopology")));
            }
            if (missingSignals.contains("specific_config_key_not_identified")) {
                Map<String, Object> configHint = queryWithFallback(
                        "knowledge.searchSop",
                        Map.of("query", request + " config key recommendation"),
                        Map.of("tool", "knowledge.searchSop", "recommendedConfigKey", "timeout")
                );
                supplementalSignals.put("recommendedConfigKey", String.valueOf(configHint.getOrDefault("recommendedConfigKey", "timeout")));
                supplementalSignals.put("configHintSource", String.valueOf(configHint.getOrDefault("tool", "knowledge.searchSop")));
            }
            if (!supplementalSignals.isEmpty()) {
                payload.put("supplementalSignals", supplementalSignals);
                payload.put("missingSignalsHandled", missingSignals);
            }
        }

        state.getContext().put("plannerAvailableTools", availableTools);
        state.getContext().put("plannerKnowledge", payload);
        state.addObservation("Planner queried read-only tools for target=" + target + ", tools="
                + availableTools.stream().map(tool -> String.valueOf(tool.get("name"))).toList());
        return new NodeResult(getName(), NodeStatus.SUCCESS, "Planner queried knowledge sources", payload);
    }

    private Map<String, Object> queryWithFallback(String toolName, Map<String, Object> requestPayload, Map<String, Object> fallback) {
        Map<String, Object> response = mcpClient.call(toolName, requestPayload);
        String status = String.valueOf(response.getOrDefault("status", "failed"));
        if (!"success".equalsIgnoreCase(status)) {
            return fallback;
        }
        Object body = response.get("response");
        if (body instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), v));
            converted.putIfAbsent("tool", toolName);
            return converted;
        }
        LinkedHashMap<String, Object> wrapped = new LinkedHashMap<>(fallback);
        wrapped.put("tool", toolName);
        wrapped.put("rawResponse", body);
        return wrapped;
    }

    private List<String> readMissingSignals(GraphState state) {
        Object value = state.getContext().get("plannerMissingSignals");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(v -> !v.isBlank()).toList();
        }
        return List.of();
    }

    private String planningRequest(GraphState state) {
        String currentRequest = state.getUserRequest() == null ? "" : state.getUserRequest();
        Object sessionContext = state.getContext().get("sessionContext");
        String sessionText = sessionContext == null ? "" : String.valueOf(sessionContext).trim();
        Object memoryContext = state.getContext().get("memoryContext");
        String memoryText = memoryContext == null ? "" : String.valueOf(memoryContext).trim();
        if (sessionText.isBlank() && memoryText.isBlank()) {
            return currentRequest;
        }
        StringBuilder builder = new StringBuilder();
        if (!memoryText.isBlank()) {
            builder.append(memoryText);
        }
        if (!sessionText.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(sessionText);
        }
        builder.append("\nCurrent user: ").append(currentRequest);
        return builder.toString();
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
