package com.kubeoncall.tool.k8s;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.observability.DependencyCircuitBreaker;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class KubernetesToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;
    private final DependencyCircuitBreaker circuitBreaker;

    public KubernetesToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this(toolHttpClient, properties, null);
    }

    @Autowired
    public KubernetesToolExecutor(
            ToolHttpClient toolHttpClient, KubeOnCallProperties properties, DependencyCircuitBreaker circuitBreaker) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String getExecutorKind() {
        return "kubernetes";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(
                new ToolDefinition(
                        "kubernetes.queryLogs",
                        "kubernetes",
                        "Query Kubernetes workload logs for investigation",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS),
                        List.of("namespace", "keyword", "lookbackMinutes"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.queryMetricsContext",
                        "kubernetes",
                        "Query Kubernetes runtime context related to metrics and workload health",
                        true,
                        false,
                        List.of(TaskType.QUERY_METRICS),
                        List.of("namespace", "metricNames", "windowMinutes"),
                        List.of("k8s-api", "metrics-server")),
                new ToolDefinition(
                        "kubernetes.queryEvents",
                        "kubernetes",
                        "Query Kubernetes Events by resource UID and bounded time window",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS),
                        List.of("cluster", "namespace", "startTime", "endTime"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.queryPodLogs",
                        "kubernetes",
                        "Query bounded current or previous Pod logs",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS),
                        List.of("cluster", "namespace", "previous", "tailLines"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.rolloutRestart",
                        "kubernetes",
                        "Perform rollout restart for a Kubernetes workload",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE),
                        List.of("namespace", "rolloutStrategy"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.rolloutUndo",
                        "kubernetes",
                        "Compensating rollback to a captured Kubernetes workload revision",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE),
                        List.of("namespace", "revision"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.scaleWorkload",
                        "kubernetes",
                        "Scale a Kubernetes workload to the desired replica count",
                        false,
                        true,
                        List.of(TaskType.SCALE_WORKLOAD),
                        List.of("namespace", "replicas"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.patchConfig",
                        "kubernetes",
                        "Patch workload configuration or related runtime settings",
                        false,
                        true,
                        List.of(TaskType.PATCH_CONFIG),
                        List.of("namespace", "configKey", "desiredValue"),
                        List.of("k8s-api", "configmap")),
                new ToolDefinition(
                        "kubernetes.getPods",
                        "kubernetes",
                        "List pods for the workload target",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS, TaskType.RESTART_SERVICE),
                        List.of("namespace"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.describeWorkload",
                        "kubernetes",
                        "Describe workload details and rollout conditions",
                        true,
                        false,
                        List.of(
                                TaskType.QUERY_LOGS,
                                TaskType.QUERY_METRICS,
                                TaskType.RESTART_SERVICE,
                                TaskType.SCALE_WORKLOAD,
                                TaskType.PATCH_CONFIG),
                        List.of("namespace"),
                        List.of("k8s-api")),
                new ToolDefinition(
                        "kubernetes.describeResource",
                        "kubernetes",
                        "Describe a Kubernetes resource (node/pod/deployment) for safe read-only diagnosis",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS),
                        List.of("resourceType", "resourceName"),
                        List.of("k8s-api")));
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        ToolDefinition definition = supportedTools().stream()
                .filter(tool -> tool.name().equals(getExecutorKind() + "." + action))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            return Map.of(
                    "status",
                    "failed",
                    "httpStatus",
                    400,
                    "errorType",
                    "UNSUPPORTED_KUBERNETES_ACTION",
                    "errorMessage",
                    "The Kubernetes action is not registered");
        }
        boolean mutating = !definition.readOnly();
        KubeOnCallProperties.Endpoint config = properties.getIntegrations().getKubernetes();
        String endpoint = mutating ? config.getMutationEndpoint() : config.getEndpoint();
        if (mutating && (endpoint == null || endpoint.isBlank())) {
            return Map.of(
                    "status",
                    "failed",
                    "httpStatus",
                    503,
                    "errorType",
                    "MUTATION_ADAPTER_NOT_CONFIGURED",
                    "errorMessage",
                    "The independent Kubernetes mutation adapter is not configured");
        }
        Map<String, Object> request = Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters);
        Map<String, String> headers = authorizationHeaders(mutating);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executor", getExecutorKind());
        metadata.put("action", action);
        metadata.put("parameters", parameters);
        String dependency = mutating ? "kubernetes-mutation" : "kubernetes";
        metadata.put("targetSystem", dependency);
        if (circuitBreaker == null) {
            return toolHttpClient.post(endpoint, request, config.getTimeoutMillis(), headers, metadata);
        }
        try {
            return circuitBreaker.execute(dependency, () -> {
                Map<String, Object> result =
                        toolHttpClient.post(endpoint, request, config.getTimeoutMillis(), headers, metadata);
                if (isDependencyFailure(result)) {
                    throw new DependencyCallFailedException(result);
                }
                return result;
            });
        } catch (DependencyCallFailedException ex) {
            return ex.result();
        } catch (DependencyCircuitBreaker.CircuitOpenException ex) {
            LinkedHashMap<String, Object> rejected = new LinkedHashMap<>(metadata);
            rejected.put("status", "failed");
            rejected.put("httpStatus", 503);
            rejected.put("errorType", "DEPENDENCY_CIRCUIT_OPEN");
            rejected.put("errorMessage", "The Kubernetes dependency circuit is open");
            return Map.copyOf(rejected);
        }
    }

    private static boolean isDependencyFailure(Map<String, Object> result) {
        if (result == null) {
            return true;
        }
        int status = intValue(result.get("httpStatus"));
        if (status == 408 || status == 429 || status >= 500) {
            return true;
        }
        String errorType = String.valueOf(result.getOrDefault("errorType", ""));
        return "TimeoutError".equals(errorType)
                || "ToolTransportError".equals(errorType)
                || "ResponseTooLarge".equals(errorType);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Map<String, String> authorizationHeaders(boolean mutating) {
        KubeOnCallProperties.Endpoint config = properties.getIntegrations().getKubernetes();
        String token = mutating ? config.getMutationBearerToken() : config.getBearerToken();
        if (token == null || token.isBlank()) {
            return Map.of();
        }
        return Map.of("Authorization", "Bearer " + token.trim());
    }

    private static final class DependencyCallFailedException extends RuntimeException {

        private final Map<String, Object> result;

        DependencyCallFailedException(Map<String, Object> result) {
            super("Kubernetes dependency call failed");
            this.result = result == null
                    ? Map.of(
                            "status",
                            "failed",
                            "httpStatus",
                            503,
                            "errorType",
                            "EMPTY_DEPENDENCY_RESPONSE",
                            "errorMessage",
                            "The Kubernetes dependency returned no result")
                    : result;
        }

        Map<String, Object> result() {
            return result;
        }
    }
}
