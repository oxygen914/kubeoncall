package com.kubeoncall.agent.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.mcp.McpClient;
import com.kubeoncall.tool.mcp.McpToolRegistry;

/** Selects and invokes discovered read-only MCP tools without hard-coded tool names. */
@Component
public class DynamicMcpEvidenceCollector {

    private static final Set<String> FIXED_EVIDENCE_TOOLS = Set.of(
            "knowledge.searchSop",
            "topology.getServiceTopology",
            "cmdb.getServiceMetadata",
            "kubernetes.describeResource",
            "prometheus.queryRange",
            "alerts.getActiveAlerts");

    private final McpClient mcpClient;
    private final McpToolRegistry toolRegistry;
    private final KubeOnCallProperties properties;

    public DynamicMcpEvidenceCollector(
            McpClient mcpClient, McpToolRegistry toolRegistry, KubeOnCallProperties properties) {
        this.mcpClient = mcpClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    public Result collect(String request, String target, String namespace, List<String> missingSignals) {
        if (!properties.getMcp().isDynamicInvocationEnabled()) {
            return Result.empty();
        }
        List<ScoredTool> selected = toolRegistry.discoveredPlannerTools().stream()
                .filter(ToolDefinition::readOnly)
                .filter(tool -> !tool.requiresApproval())
                .filter(tool -> !FIXED_EVIDENCE_TOOLS.contains(tool.name()))
                .map(tool -> new ScoredTool(tool, score(tool, request, missingSignals)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingInt(ScoredTool::score).reversed().thenComparing(item -> item.tool()
                        .name()))
                .limit(Math.max(1, properties.getMcp().getDynamicMaxTools()))
                .toList();

        List<Map<String, Object>> invocations = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (ScoredTool item : selected) {
            ParameterResolution resolution = parameters(item.tool(), request, target, namespace, missingSignals);
            if (!resolution.missing().isEmpty()) {
                skipped.add(Map.of(
                        "tool", item.tool().name(),
                        "reason", "required_parameters_unresolved",
                        "missingParameters", resolution.missing()));
                continue;
            }
            Map<String, Object> response = mcpClient.call(item.tool().name(), resolution.parameters());
            invocations.add(invocation(item.tool(), resolution.parameters(), response));
        }
        return new Result(invocations, skipped);
    }

    private int score(ToolDefinition tool, String request, List<String> missingSignals) {
        String context = normalize((request == null ? "" : request) + " " + String.join(" ", safe(missingSignals)));
        String toolText = normalize(tool.name() + " " + tool.description() + " "
                + String.join(" ", tool.targetSystems()) + " " + String.join(" ", tool.requiredParameters()));
        int score = 0;
        for (String token : tokens(context)) {
            if (toolText.contains(token)) {
                score += 2;
            }
        }
        score += hintScore(context, toolText);
        return score;
    }

    private int hintScore(String context, String toolText) {
        int score = 0;
        if (containsAny(context, "metric", "cpu", "memory", "指标", "监控")
                && containsAny(toolText, "metric", "prometheus", "monitor")) {
            score += 4;
        }
        if (containsAny(context, "log", "日志") && containsAny(toolText, "log", "trace")) {
            score += 4;
        }
        if (containsAny(context, "owner", "owns", "ownership", "负责人", "归属")
                && containsAny(toolText, "owner", "ownership", "metadata", "cmdb")) {
            score += 4;
        }
        if (containsAny(context, "topology", "dependency", "依赖", "拓扑")
                && containsAny(toolText, "topology", "dependency")) {
            score += 4;
        }
        if (containsAny(context, "alert", "告警") && containsAny(toolText, "alert", "alarm")) {
            score += 4;
        }
        return score;
    }

    private ParameterResolution parameters(
            ToolDefinition tool, String request, String target, String namespace, List<String> missingSignals) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String parameter : safe(tool.requiredParameters())) {
            Object value = parameterValue(parameter, request, target, namespace, missingSignals);
            if (value == null) {
                missing.add(parameter);
            } else {
                resolved.put(parameter, value);
            }
        }
        return new ParameterResolution(resolved, missing);
    }

    private Object parameterValue(
            String parameter, String request, String target, String namespace, List<String> missingSignals) {
        String normalized = parameter == null ? "" : parameter.replace("_", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "query", "question", "text", "request" -> request;
            case "servicename", "service", "target", "resourcename", "resource" -> knownTarget(target);
            case "namespace" -> namespace;
            case "environment" -> "prod".equals(namespace) ? "production" : namespace;
            case "windowminutes" -> 10;
            case "limit", "topk" -> 5;
            case "missingsignals" -> safe(missingSignals);
            default -> null;
        };
    }

    private Map<String, Object> invocation(
            ToolDefinition tool, Map<String, Object> parameters, Map<String, Object> response) {
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("tool", tool.name());
        invocation.put("parameters", parameters);
        invocation.put("status", response == null ? "failed" : response.getOrDefault("status", "failed"));
        if (response != null && response.get("response") != null) {
            invocation.put("response", response.get("response"));
        }
        if (response != null && response.get("errorType") != null) {
            invocation.put("errorType", response.get("errorType"));
        }
        if (response != null && response.get("httpStatus") != null) {
            invocation.put("httpStatus", response.get("httpStatus"));
        }
        return invocation;
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(value).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String knownTarget(String target) {
        return target == null || target.isBlank() || "unknown-service".equals(target) ? null : target;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record Result(List<Map<String, Object>> invocations, List<Map<String, Object>> skipped) {
        public Result {
            invocations = invocations == null ? List.of() : List.copyOf(invocations);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }

        public static Result empty() {
            return new Result(List.of(), List.of());
        }

        public boolean attempted() {
            return !invocations.isEmpty() || !skipped.isEmpty();
        }
    }

    private record ScoredTool(ToolDefinition tool, int score) {}

    private record ParameterResolution(Map<String, Object> parameters, List<String> missing) {}
}
