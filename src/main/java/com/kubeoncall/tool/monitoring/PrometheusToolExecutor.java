package com.kubeoncall.tool.monitoring;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrometheusToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public PrometheusToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public String getExecutorKind() {
        return "prometheus";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(
                new ToolDefinition(
                        "prometheus.instantQuery",
                        "prometheus",
                        "Run an instant PromQL query for current operational state",
                        true,
                        false,
                        List.of(TaskType.QUERY_METRICS),
                        List.of("query"),
                        List.of("prometheus")
                ),
                new ToolDefinition(
                        "prometheus.rangeQuery",
                        "prometheus",
                        "Run a range PromQL query for historical trends",
                        true,
                        false,
                        List.of(TaskType.QUERY_METRICS),
                        List.of("query", "windowMinutes"),
                        List.of("prometheus")
                )
        );
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        Map<String, Object> request = Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executor", getExecutorKind());
        metadata.put("action", action);
        metadata.put("parameters", parameters);
        metadata.put("targetSystem", "prometheus");
        metadata.put("query", parameters.getOrDefault("query", "up"));
        return toolHttpClient.post(
                properties.getIntegrations().getPrometheus().getEndpoint(),
                request,
                properties.getIntegrations().getPrometheus().getTimeoutMillis(),
                metadata
        );
    }
}
