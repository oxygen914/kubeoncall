package com.kubeoncall.tool.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;

@Component
public class McpToolRegistry {

    private final McpClient mcpClient;
    private final KubeOnCallProperties properties;
    private volatile List<ToolDefinition> discoveredTools = List.of();
    private volatile Instant discoveredAt = Instant.EPOCH;

    public McpToolRegistry() {
        this(null, null);
    }

    @Autowired
    public McpToolRegistry(McpClient mcpClient, KubeOnCallProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
    }

    public List<ToolDefinition> listPlannerTools() {
        List<ToolDefinition> dynamic = refreshDiscoveredTools();
        if (dynamic.isEmpty()) {
            return staticPlannerTools();
        }
        Map<String, ToolDefinition> merged = new LinkedHashMap<>();
        staticPlannerTools().forEach(tool -> merged.put(tool.name(), tool));
        dynamic.forEach(tool -> merged.put(tool.name(), tool));
        return List.copyOf(merged.values());
    }

    public synchronized List<ToolDefinition> refreshDiscoveredTools() {
        if (!discoveryConfigured()) {
            return List.of();
        }
        Instant now = Instant.now();
        if (now.isBefore(
                discoveredAt.plusSeconds(Math.max(1, properties.getMcp().getDiscoveryCacheSeconds())))) {
            return discoveredTools;
        }
        try {
            List<ToolDefinition> parsed = mcpClient.listTools().stream()
                    .map(this::toToolDefinition)
                    .flatMap(java.util.Optional::stream)
                    .filter(ToolDefinition::readOnly)
                    .toList();
            if (!parsed.isEmpty()) {
                discoveredTools = List.copyOf(parsed);
            }
        } catch (RuntimeException ignored) {
            // Preserve the last known discovered set, then fall back to static tools if absent.
        }
        discoveredAt = now;
        return discoveredTools;
    }

    private boolean discoveryConfigured() {
        return mcpClient != null && properties != null && properties.getMcp().isDiscoveryEnabled();
    }

    private List<ToolDefinition> staticPlannerTools() {
        return List.of(
                new ToolDefinition(
                        "knowledge.searchSop",
                        "knowledge",
                        "Search SOPs, runbooks, and historical remediation knowledge",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("query"),
                        List.of("knowledge-base")),
                new ToolDefinition(
                        "topology.getServiceTopology",
                        "topology",
                        "Fetch upstream and downstream service dependency topology",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("cmdb", "service-mesh")),
                new ToolDefinition(
                        "cmdb.getServiceMetadata",
                        "cmdb",
                        "Load service ownership, namespace, environment, and criticality metadata",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("cmdb")),
                new ToolDefinition(
                        "kubernetes.describeResource",
                        "kubernetes",
                        "Describe Kubernetes resource metadata without changing cluster state",
                        true,
                        false,
                        List.of(
                                TaskType.QUERY_LOGS,
                                TaskType.QUERY_METRICS,
                                TaskType.RESTART_SERVICE,
                                TaskType.SCALE_WORKLOAD,
                                TaskType.PATCH_CONFIG),
                        List.of("namespace", "resourceName"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "prometheus.queryRange",
                        "prometheus",
                        "Query historical metrics trends for diagnostics and planning",
                        true,
                        false,
                        List.of(
                                TaskType.QUERY_METRICS,
                                TaskType.RESTART_SERVICE,
                                TaskType.SCALE_WORKLOAD,
                                TaskType.PATCH_CONFIG),
                        List.of("query", "windowMinutes"),
                        List.of("prometheus")),
                new ToolDefinition(
                        "alerts.getActiveAlerts",
                        "alerts",
                        "Fetch active alerts, silences, and routing labels for the target service",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("alertmanager")));
    }

    private java.util.Optional<ToolDefinition> toToolDefinition(Map<String, Object> raw) {
        String name = text(raw, "name");
        if (name.isBlank()) {
            return java.util.Optional.empty();
        }
        boolean readOnly = bool(raw, "readOnly", true);
        boolean requiresApproval = bool(raw, "requiresApproval", false);
        if (!readOnly || requiresApproval) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ToolDefinition(
                name,
                defaultString(text(raw, "executorKind"), namespace(name)),
                defaultString(text(raw, "description"), "Discovered MCP tool " + name),
                true,
                false,
                taskTypes(raw.get("supportedTaskTypes")),
                stringList(raw.get("requiredParameters")),
                stringList(raw.get("targetSystems"))));
    }

    private String text(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean bool(Map<String, Object> raw, String key, boolean fallback) {
        Object value = raw.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<TaskType> taskTypes(Object value) {
        List<TaskType> parsed = new ArrayList<>();
        for (String item : stringList(value)) {
            try {
                parsed.add(TaskType.valueOf(item.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // An unsupported remote task type must not invalidate all discovered tools.
            }
        }
        return parsed.isEmpty() ? List.of(TaskType.values()) : List.copyOf(parsed);
    }

    private String namespace(String toolName) {
        int separator = toolName.indexOf('.');
        return separator > 0 ? toolName.substring(0, separator) : "mcp";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
