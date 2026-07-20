package com.kubeoncall.tool.k8s;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.tool.http.ToolHttpClient;

@Component
public class KubernetesToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public KubernetesToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
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
                        "kubernetes.rolloutRestart",
                        "kubernetes",
                        "Perform rollout restart for a Kubernetes workload",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE),
                        List.of("namespace", "rolloutStrategy"),
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
        Map<String, Object> request = Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executor", getExecutorKind());
        metadata.put("action", action);
        metadata.put("parameters", parameters);
        metadata.put("targetSystem", "k8s-api");
        return toolHttpClient.post(
                properties.getIntegrations().getKubernetes().getEndpoint(),
                request,
                properties.getIntegrations().getKubernetes().getTimeoutMillis(),
                metadata);
    }
}
