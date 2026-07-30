package com.kubeoncall.tool.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;

@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final McpClient mcpClient;
    private final KubeOnCallProperties properties;
    private final McpToolPolicy toolPolicy;
    private volatile List<ToolDefinition> discoveredTools = List.of();
    private volatile Instant discoveredAt = Instant.EPOCH;

    public McpToolRegistry() {
        this(null, null);
    }

    @Autowired
    public McpToolRegistry(McpClient mcpClient, KubeOnCallProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
        this.toolPolicy = properties == null ? null : new McpToolPolicy(properties);
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
            discoveredTools = List.copyOf(parsed);
        } catch (RuntimeException ex) {
            // Preserve the last known discovered set, then fall back to static tools if absent.
            log.warn(
                    "MCP tool discovery failed; preserving last successful snapshot: lastKnownCount={}, errorType={}",
                    discoveredTools.size(),
                    ex.getClass().getSimpleName());
        }
        discoveredAt = now;
        return discoveredTools;
    }

    public List<ToolDefinition> discoveredPlannerTools() {
        return refreshDiscoveredTools();
    }

    @Scheduled(fixedDelayString = "${kubeoncall.mcp.discovery-refresh-millis:60000}")
    public void scheduledRefresh() {
        if (discoveryConfigured()) {
            discoveredAt = Instant.EPOCH;
            refreshDiscoveredTools();
        }
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
                        List.of("alertmanager")),
                new ToolDefinition(
                        "changes.getRecentChanges",
                        "changes",
                        "Load bounded deployment and infrastructure changes from the execution evidence window",
                        true,
                        false,
                        List.of(TaskType.values()),
                        List.of("serviceName"),
                        List.of("change-read-model")));
    }

    private java.util.Optional<ToolDefinition> toToolDefinition(Map<String, Object> raw) {
        String name = text(raw, "name");
        if (name.isBlank()) {
            return java.util.Optional.empty();
        }
        if (toolPolicy == null || !toolPolicy.allowed(name)) {
            return java.util.Optional.empty();
        }
        boolean standardJsonRpc = properties != null
                && "jsonrpc".equalsIgnoreCase(properties.getMcp().getProtocol());
        Map<String, Object> annotations = stringMap(raw.get("annotations"));
        boolean readOnly = standardJsonRpc ? bool(annotations, "readOnlyHint", false) : bool(raw, "readOnly", true);
        boolean requiresApproval = standardJsonRpc
                ? bool(annotations, "destructiveHint", false) || !readOnly
                : bool(raw, "requiresApproval", false);
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
                requiredParameters(raw),
                stringList(raw.get("targetSystems")),
                stringMap(raw.get("inputSchema"))));
    }

    private List<String> requiredParameters(Map<String, Object> raw) {
        List<String> explicit = stringList(raw.get("requiredParameters"));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        Map<String, Object> inputSchema = stringMap(raw.get("inputSchema"));
        return stringList(inputSchema.get("required"));
    }

    private Map<String, Object> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        values.forEach((key, item) -> converted.put(String.valueOf(key), item));
        return converted;
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
