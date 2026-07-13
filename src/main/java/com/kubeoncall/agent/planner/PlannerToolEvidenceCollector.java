package com.kubeoncall.agent.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.tool.mcp.McpClient;

/** Collects the planner's read-only MCP evidence and preserves deterministic fallback evidence. */
@Component
public class PlannerToolEvidenceCollector {

    private final McpClient mcpClient;

    public PlannerToolEvidenceCollector(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    public Evidence collect(String request, List<String> missingSignals) {
        String target = inferTarget(request);
        String namespace = inferNamespace(request);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(
                "sop",
                queryWithFallback(
                        "knowledge.searchSop",
                        Map.of("query", request),
                        Map.of(
                                "tool",
                                "knowledge.searchSop",
                                "documentId",
                                "SOP-GENERAL-001",
                                "title",
                                "Standard investigation procedure for " + target,
                                "matchedIntent",
                                inferIntent(request))));
        evidence.put(
                "topology",
                queryWithFallback(
                        "topology.getServiceTopology",
                        Map.of("serviceName", target),
                        Map.of(
                                "tool",
                                "topology.getServiceTopology",
                                "service",
                                target,
                                "upstreams",
                                List.of("gateway-service"),
                                "downstreams",
                                List.of("mysql", "redis"))));
        evidence.put(
                "serviceMetadata",
                queryWithFallback(
                        "cmdb.getServiceMetadata",
                        Map.of("serviceName", target),
                        Map.of(
                                "tool",
                                "cmdb.getServiceMetadata",
                                "service",
                                target,
                                "namespace",
                                namespace,
                                "environment",
                                inferEnvironment(request),
                                "criticality",
                                inferCriticality(target),
                                "owner",
                                "lab-ops")));
        evidence.put(
                "resourceSnapshot",
                queryWithFallback(
                        "kubernetes.describeResource",
                        Map.of("resourceName", target, "namespace", namespace),
                        Map.of(
                                "tool",
                                "kubernetes.describeResource",
                                "resourceName",
                                target,
                                "namespace",
                                namespace,
                                "kind",
                                "Deployment",
                                "status",
                                "Healthy")));
        evidence.put(
                "activeAlerts",
                queryWithFallback(
                        "alerts.getActiveAlerts",
                        Map.of("serviceName", target),
                        Map.of(
                                "tool",
                                "alerts.getActiveAlerts",
                                "service",
                                target,
                                "count",
                                request.contains("告警") || request.toLowerCase().contains("alert") ? 2 : 0,
                                "labels",
                                List.of("service=" + target, "severity=warning"))));
        String metricQuery = "rate(http_requests_total{service=\"" + target + "\"}[5m])";
        evidence.put(
                "metricsContext",
                queryWithFallback(
                        "prometheus.queryRange",
                        Map.of("query", metricQuery, "windowMinutes", 10),
                        Map.of(
                                "tool",
                                "prometheus.queryRange",
                                "query",
                                metricQuery,
                                "windowMinutes",
                                10,
                                "trend",
                                "stable")));
        addSupplementalSignals(evidence, request, target, missingSignals);
        return new Evidence(target, evidence);
    }

    private void addSupplementalSignals(
            Map<String, Object> evidence, String request, String target, List<String> missingSignals) {
        if (missingSignals.isEmpty()) {
            return;
        }
        Map<String, Object> supplementalSignals = new LinkedHashMap<>();
        if (missingSignals.contains("target_replica_count_not_specified")) {
            Map<String, Object> scaleHint = queryWithFallback(
                    "topology.getServiceTopology",
                    Map.of("serviceName", target, "mode", "capacity_hint"),
                    Map.of("tool", "topology.getServiceTopology", "recommendedReplicas", 3));
            Object replicas = scaleHint.get("recommendedReplicas");
            supplementalSignals.put("recommendedReplicas", replicas instanceof Number number ? number.intValue() : 3);
            supplementalSignals.put(
                    "scaleHintSource", String.valueOf(scaleHint.getOrDefault("tool", "topology.getServiceTopology")));
        }
        if (missingSignals.contains("specific_config_key_not_identified")) {
            Map<String, Object> configHint = queryWithFallback(
                    "knowledge.searchSop",
                    Map.of("query", request + " config key recommendation"),
                    Map.of("tool", "knowledge.searchSop", "recommendedConfigKey", "timeout"));
            supplementalSignals.put(
                    "recommendedConfigKey", String.valueOf(configHint.getOrDefault("recommendedConfigKey", "timeout")));
            supplementalSignals.put(
                    "configHintSource", String.valueOf(configHint.getOrDefault("tool", "knowledge.searchSop")));
        }
        if (!supplementalSignals.isEmpty()) {
            evidence.put("supplementalSignals", supplementalSignals);
            evidence.put("missingSignalsHandled", missingSignals);
        }
    }

    private Map<String, Object> queryWithFallback(
            String toolName, Map<String, Object> requestPayload, Map<String, Object> fallback) {
        Map<String, Object> response = mcpClient.call(toolName, requestPayload);
        String status = String.valueOf(response.getOrDefault("status", "failed"));
        if (!"success".equalsIgnoreCase(status)) {
            return fallback;
        }
        Object body = response.get("response");
        if (body instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> converted.put(String.valueOf(key), value));
            converted.putIfAbsent("tool", toolName);
            return converted;
        }
        LinkedHashMap<String, Object> wrapped = new LinkedHashMap<>(fallback);
        wrapped.put("tool", toolName);
        wrapped.put("rawResponse", body);
        return wrapped;
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

    public record Evidence(String target, Map<String, Object> payload) {

        public Evidence {
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
