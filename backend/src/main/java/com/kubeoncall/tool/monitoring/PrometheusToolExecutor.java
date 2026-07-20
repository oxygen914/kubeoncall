package com.kubeoncall.tool.monitoring;

import java.time.Instant;
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
                        List.of("prometheus")),
                new ToolDefinition(
                        "prometheus.rangeQuery",
                        "prometheus",
                        "Run a range PromQL query for historical trends",
                        true,
                        false,
                        List.of(TaskType.QUERY_METRICS),
                        List.of("query", "windowMinutes"),
                        List.of("prometheus")));
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        String endpoint = properties.getIntegrations().getPrometheus().getEndpoint();
        if (isNativeEndpoint(endpoint)) {
            return executeNative(endpoint, action, parameters);
        }
        Map<String, Object> request = Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters);
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
                metadata);
    }

    private Map<String, Object> executeNative(String endpoint, String action, Map<String, Object> parameters) {
        boolean range = "rangeQuery".equals(action);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("query", parameters.getOrDefault("query", "up"));
        if (range) {
            int windowMinutes = integer(parameters.get("windowMinutes"), 15);
            Instant end = Instant.now();
            query.put("start", end.minusSeconds(windowMinutes * 60L).getEpochSecond());
            query.put("end", end.getEpochSecond());
            query.put("step", Math.max(15, windowMinutes * 60 / 240));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("executor", getExecutorKind());
        metadata.put("action", action);
        metadata.put("parameters", parameters);
        metadata.put("targetSystem", "prometheus");
        metadata.put("query", query.get("query"));
        String path = range ? "/api/v1/query_range" : "/api/v1/query";
        Map<String, Object> result = toolHttpClient.get(
                endpoint.replaceAll("/+$", "") + path,
                query,
                properties.getIntegrations().getPrometheus().getTimeoutMillis(),
                Map.of(),
                metadata,
                4 * 1024 * 1024);
        return validatePrometheusResponse(result);
    }

    private Map<String, Object> validatePrometheusResponse(Map<String, Object> result) {
        if (!"success".equalsIgnoreCase(String.valueOf(result.get("status")))) {
            return result;
        }
        Object body = result.get("response");
        if (!(body instanceof Map<?, ?> response)
                || !"success".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            Map<String, Object> failed = new LinkedHashMap<>(result);
            failed.put("status", "failed");
            failed.put("errorType", "PrometheusApiError");
            failed.put("errorMessage", "Prometheus API returned an invalid or failed response");
            return Map.copyOf(failed);
        }
        return result;
    }

    private boolean isNativeEndpoint(String endpoint) {
        return endpoint != null && !endpoint.isBlank() && !endpoint.contains("/api/tools/");
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
