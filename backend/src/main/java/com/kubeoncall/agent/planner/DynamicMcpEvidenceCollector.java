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
import com.kubeoncall.skill.SkillExecutionPolicy;
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
        return collect(request, target, namespace, missingSignals, SkillExecutionPolicy.ToolAccess.unrestricted());
    }

    public Result collect(
            String request,
            String target,
            String namespace,
            List<String> missingSignals,
            SkillExecutionPolicy.ToolAccess toolAccess) {
        if (!properties.getMcp().isDynamicInvocationEnabled()) {
            return Result.empty();
        }
        SkillExecutionPolicy.ToolAccess access =
                toolAccess == null ? SkillExecutionPolicy.ToolAccess.unrestricted() : toolAccess;
        List<ScoredTool> selected = toolRegistry.discoveredPlannerTools().stream()
                .filter(ToolDefinition::readOnly)
                .filter(tool -> !tool.requiresApproval())
                .filter(tool -> access.allows(tool.name()))
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
        Map<String, Object> schemas = propertySchemas(tool);
        for (String parameter : safe(tool.requiredParameters())) {
            Object value = parameterValue(tool, parameter, request, target, namespace, missingSignals);
            Object normalized = normalizeSchemaValue(value, schemas.get(parameter));
            if (normalized == null) {
                missing.add(parameter);
            } else {
                resolved.put(parameter, normalized);
            }
        }
        schemas.forEach((parameter, schema) -> {
            if (resolved.containsKey(parameter) || !hasAutomaticValue(schema)) {
                return;
            }
            Object value = schemaValue(schema, request);
            Object normalized = normalizeSchemaValue(value, schema);
            if (normalized != null) {
                resolved.put(parameter, normalized);
            }
        });
        return new ParameterResolution(resolved, missing);
    }

    private boolean hasAutomaticValue(Object rawSchema) {
        if (!(rawSchema instanceof Map<?, ?> schema)) {
            return false;
        }
        return schema.get("default") != null
                || schema.get("const") != null
                || schema.get("enum") instanceof List<?> values && values.size() == 1;
    }

    private Object parameterValue(
            ToolDefinition tool,
            String parameter,
            String request,
            String target,
            String namespace,
            List<String> missingSignals) {
        String normalized = parameter == null ? "" : parameter.replace("_", "").toLowerCase(Locale.ROOT);
        Object known =
                switch (normalized) {
                    case "query", "question", "text", "request" -> request;
                    case "servicename", "service", "target", "resourcename", "resource" -> knownTarget(target);
                    case "namespace" -> namespace;
                    case "environment" -> "prod".equals(namespace) ? "production" : namespace;
                    case "windowminutes" -> 10;
                    case "limit", "topk" -> 5;
                    case "missingsignals" -> safe(missingSignals);
                    default -> null;
                };
        Object schema = propertySchemas(tool).get(parameter);
        return known == null ? schemaValue(schema, request) : normalizeSchemaValue(known, schema);
    }

    private Map<String, Object> propertySchemas(ToolDefinition tool) {
        Object properties = tool.inputSchema().get("properties");
        if (!(properties instanceof Map<?, ?> propertyMap)) {
            return Map.of();
        }
        Map<String, Object> schemas = new LinkedHashMap<>();
        propertyMap.forEach((key, value) -> schemas.put(String.valueOf(key), value));
        return schemas;
    }

    private Object schemaValue(Object rawSchema, String request) {
        if (!(rawSchema instanceof Map<?, ?> schema)) {
            return null;
        }
        if (schema.get("default") != null) {
            return schema.get("default");
        }
        if (schema.get("const") != null) {
            return schema.get("const");
        }
        if (schema.get("enum") instanceof List<?> values && !values.isEmpty()) {
            if (values.size() == 1) {
                return values.get(0);
            }
            String context = normalize(request);
            for (Object value : values) {
                if (!String.valueOf(value).isBlank() && context.contains(normalize(String.valueOf(value)))) {
                    return value;
                }
            }
        }
        Object alternatives = schema.get("oneOf") == null ? schema.get("anyOf") : schema.get("oneOf");
        if (alternatives instanceof List<?> schemas) {
            for (Object alternative : schemas) {
                Object value = schemaValue(alternative, request);
                if (value != null) {
                    return value;
                }
            }
        }
        Object rawType = schema.get("type");
        String type = rawType == null ? "" : String.valueOf(rawType);
        if ("object".equalsIgnoreCase(type)) {
            return objectSchemaValue(schema, request);
        }
        if ("array".equalsIgnoreCase(type)) {
            Object item = schemaValue(schema.get("items"), request);
            return item == null ? null : List.of(item);
        }
        if ("boolean".equalsIgnoreCase(type) && request != null) {
            String normalizedRequest = normalize(request);
            if (normalizedRequest.contains(" true") || normalizedRequest.contains(" enabled")) {
                return true;
            }
            if (normalizedRequest.contains(" false") || normalizedRequest.contains(" disabled")) {
                return false;
            }
        }
        if ("string".equalsIgnoreCase(type) && request != null && !request.isBlank()) {
            int maxLength = integer(schema.get("maxLength"), 2000);
            return request.length() <= maxLength ? request : request.substring(0, maxLength);
        }
        return null;
    }

    private Object objectSchemaValue(Map<?, ?> schema, String request) {
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return null;
        }
        Set<String> required = new LinkedHashSet<>();
        if (schema.get("required") instanceof List<?> values) {
            values.forEach(value -> required.add(String.valueOf(value)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = schemaValue(entry.getValue(), request);
            value = normalizeSchemaValue(value, entry.getValue());
            if (value != null) {
                result.put(name, value);
            } else if (required.contains(name)) {
                return null;
            }
        }
        return result.isEmpty() ? null : result;
    }

    private Object normalizeSchemaValue(Object value, Object rawSchema) {
        if (value == null || !(rawSchema instanceof Map<?, ?> schema)) {
            return value;
        }
        if (schema.get("enum") instanceof List<?> values && !values.contains(value)) {
            return null;
        }
        String type = schema.get("type") == null ? "" : String.valueOf(schema.get("type"));
        try {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "string" -> boundedString(String.valueOf(value), schema);
                case "integer" -> boundedNumber(value, schema, true);
                case "number" -> boundedNumber(value, schema, false);
                case "boolean" -> booleanValue(value);
                case "array" -> value instanceof List<?> ? value : List.of(value);
                case "object" -> value instanceof Map<?, ?> ? value : null;
                default -> value;
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String boundedString(String value, Map<?, ?> schema) {
        int maxLength = integer(schema.get("maxLength"), 2000);
        if (value.length() > maxLength) {
            return value.substring(0, maxLength);
        }
        int minLength = integer(schema.get("minLength"), 0);
        return value.length() < minLength ? null : value;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return null;
    }

    private Number boundedNumber(Object value, Map<?, ?> schema, boolean integer) {
        double parsed =
                value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
        if (schema.get("minimum") instanceof Number minimum) {
            parsed = Math.max(parsed, minimum.doubleValue());
        }
        if (schema.get("maximum") instanceof Number maximum) {
            parsed = Math.min(parsed, maximum.doubleValue());
        }
        if (integer) {
            return (int) Math.round(parsed);
        }
        return parsed;
    }

    private int integer(Object value, int fallback) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : fallback;
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
