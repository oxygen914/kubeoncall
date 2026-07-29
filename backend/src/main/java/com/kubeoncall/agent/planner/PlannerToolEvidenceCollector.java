package com.kubeoncall.agent.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.observability.SensitiveDataRedactor;
import com.kubeoncall.tool.mcp.McpClient;

/**
 * Collects planner read-only MCP evidence.
 *
 * <p>Dependency failures are represented as unavailable evidence. The collector deliberately never
 * substitutes fabricated SOPs, topology, health, alerts or metric values.
 */
@Component
public class PlannerToolEvidenceCollector {

    private static final SensitiveDataRedactor REDACTOR = SensitiveDataRedactor.STANDARD;

    private final McpClient mcpClient;
    private final DynamicMcpEvidenceCollector dynamicMcpEvidenceCollector;

    public PlannerToolEvidenceCollector(McpClient mcpClient) {
        this(mcpClient, null);
    }

    @Autowired
    public PlannerToolEvidenceCollector(McpClient mcpClient, DynamicMcpEvidenceCollector dynamicMcpEvidenceCollector) {
        this.mcpClient = mcpClient;
        this.dynamicMcpEvidenceCollector = dynamicMcpEvidenceCollector;
    }

    public Evidence collect(String request, List<String> missingSignals) {
        String target = inferTarget(request);
        String namespace = inferNamespace(request);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sop", queryWithFallback("knowledge.searchSop", Map.of("query", request)));
        evidence.put("topology", queryWithFallback("topology.getServiceTopology", Map.of("serviceName", target)));
        evidence.put("serviceMetadata", queryWithFallback("cmdb.getServiceMetadata", Map.of("serviceName", target)));
        evidence.put(
                "resourceSnapshot",
                queryWithFallback(
                        "kubernetes.describeResource", Map.of("resourceName", target, "namespace", namespace)));
        evidence.put("activeAlerts", queryWithFallback("alerts.getActiveAlerts", Map.of("serviceName", target)));
        String metricQuery = "rate(http_requests_total{service=\"" + target + "\"}[5m])";
        evidence.put(
                "metricsContext",
                queryWithFallback("prometheus.queryRange", Map.of("query", metricQuery, "windowMinutes", 10)));
        addDynamicMcpEvidence(evidence, request, target, namespace, missingSignals);
        addSupplementalSignals(evidence, request, target, missingSignals);
        return new Evidence(target, evidence);
    }

    private void addDynamicMcpEvidence(
            Map<String, Object> evidence,
            String request,
            String target,
            String namespace,
            List<String> missingSignals) {
        if (dynamicMcpEvidenceCollector == null) {
            return;
        }
        DynamicMcpEvidenceCollector.Result result =
                dynamicMcpEvidenceCollector.collect(request, target, namespace, missingSignals);
        if (!result.attempted()) {
            return;
        }
        evidence.put("dynamicMcpInvocations", REDACTOR.redact(result.invocations()));
        evidence.put("dynamicMcpSkipped", REDACTOR.redact(result.skipped()));
        evidence.put(
                "dynamicMcpTools",
                result.invocations().stream()
                        .map(item -> String.valueOf(item.get("tool")))
                        .toList());
    }

    private void addSupplementalSignals(
            Map<String, Object> evidence, String request, String target, List<String> missingSignals) {
        if (missingSignals.isEmpty()) {
            return;
        }
        Map<String, Object> supplementalSignals = new LinkedHashMap<>();
        if (missingSignals.contains("target_replica_count_not_specified")) {
            Map<String, Object> scaleHint = queryWithFallback(
                    "topology.getServiceTopology", Map.of("serviceName", target, "mode", "capacity_hint"));
            Object replicas = scaleHint.get("recommendedReplicas");
            if (replicas instanceof Number number) {
                supplementalSignals.put("recommendedReplicas", number.intValue());
                supplementalSignals.put(
                        "scaleHintSource",
                        String.valueOf(scaleHint.getOrDefault("tool", "topology.getServiceTopology")));
            }
        }
        if (missingSignals.contains("specific_config_key_not_identified")) {
            Map<String, Object> configHint =
                    queryWithFallback("knowledge.searchSop", Map.of("query", request + " config key recommendation"));
            Object configKey = configHint.get("recommendedConfigKey");
            if (configKey != null && !String.valueOf(configKey).isBlank()) {
                supplementalSignals.put("recommendedConfigKey", String.valueOf(configKey));
                supplementalSignals.put(
                        "configHintSource", String.valueOf(configHint.getOrDefault("tool", "knowledge.searchSop")));
            }
        }
        if (!supplementalSignals.isEmpty()) {
            evidence.put("supplementalSignals", supplementalSignals);
            evidence.put("missingSignalsHandled", missingSignals);
        }
    }

    private Map<String, Object> queryWithFallback(String toolName, Map<String, Object> requestPayload) {
        Map<String, Object> response;
        try {
            response = mcpClient.call(toolName, requestPayload);
        } catch (RuntimeException ex) {
            return unavailable(toolName, ex.getClass().getSimpleName());
        }
        if (response == null) {
            return unavailable(toolName, "EMPTY_TOOL_RESPONSE");
        }
        String status = String.valueOf(response.getOrDefault("status", "failed"));
        if (!"success".equalsIgnoreCase(status)) {
            return unavailable(toolName, String.valueOf(response.getOrDefault("errorType", "DEPENDENCY_UNAVAILABLE")));
        }
        Object body = response.get("response");
        if (body instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, value) -> converted.put(String.valueOf(key), value));
            converted.putIfAbsent("tool", toolName);
            converted.put("collectionStatus", "SUCCEEDED");
            converted.put("simulation", false);
            return REDACTOR.redactMap(converted);
        }
        LinkedHashMap<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("tool", toolName);
        wrapped.put("collectionStatus", "SUCCEEDED");
        wrapped.put("simulation", false);
        wrapped.put("rawResponse", body);
        return REDACTOR.redactMap(wrapped);
    }

    private Map<String, Object> unavailable(String toolName, String errorType) {
        LinkedHashMap<String, Object> unavailable = new LinkedHashMap<>();
        unavailable.put("tool", toolName);
        unavailable.put("collectionStatus", "UNAVAILABLE");
        unavailable.put("simulation", false);
        unavailable.put("errorType", errorType == null || errorType.isBlank() ? "DEPENDENCY_UNAVAILABLE" : errorType);
        return unavailable;
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
        return "current-scope";
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

    public record Evidence(String target, Map<String, Object> payload) {

        public Evidence {
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
