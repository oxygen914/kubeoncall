package com.kubeoncall.tool.monitoring;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PrometheusToolExecutor implements ToolExecutor {

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
        return Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters,
                "targetSystem", "prometheus",
                "status", "simulated_success",
                "httpStatus", 200,
                "simulatedLatencyMs", 95,
                "query", parameters.getOrDefault("query", "up")
        );
    }
}
