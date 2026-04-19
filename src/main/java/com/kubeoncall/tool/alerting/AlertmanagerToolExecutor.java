package com.kubeoncall.tool.alerting;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AlertmanagerToolExecutor implements ToolExecutor {

    @Override
    public String getExecutorKind() {
        return "alertmanager";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(
                new ToolDefinition(
                        "alertmanager.listAlerts",
                        "alertmanager",
                        "List active alerts affecting the current workload",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS, TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("serviceName"),
                        List.of("alertmanager")
                ),
                new ToolDefinition(
                        "alertmanager.createSilence",
                        "alertmanager",
                        "Create a maintenance silence for planned changes",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("serviceName", "durationMinutes"),
                        List.of("alertmanager")
                ),
                new ToolDefinition(
                        "alertmanager.expireSilence",
                        "alertmanager",
                        "Expire an existing maintenance silence",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("silenceId"),
                        List.of("alertmanager")
                )
        );
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        return Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters,
                "targetSystem", "alertmanager",
                "status", "simulated_success",
                "httpStatus", 200,
                "simulatedLatencyMs", 110,
                "silenceId", parameters.getOrDefault("silenceId", "silence-simulated-001")
        );
    }
}
