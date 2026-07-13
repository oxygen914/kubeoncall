package com.kubeoncall.tool.alerting;

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
public class AlertmanagerToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public AlertmanagerToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

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
                        List.of(
                                TaskType.QUERY_LOGS,
                                TaskType.QUERY_METRICS,
                                TaskType.RESTART_SERVICE,
                                TaskType.SCALE_WORKLOAD,
                                TaskType.PATCH_CONFIG),
                        List.of("serviceName"),
                        List.of("alertmanager")),
                new ToolDefinition(
                        "alertmanager.sendAlertEvent",
                        "alertmanager",
                        "Send a non-mutating alert event (annotation/status update) for an alarm diagnosis",
                        true,
                        false,
                        List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS),
                        List.of("alertName"),
                        List.of("alertmanager")),
                new ToolDefinition(
                        "alertmanager.createSilence",
                        "alertmanager",
                        "Create a maintenance silence for planned changes (approval/maintenance only)",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("serviceName", "durationMinutes"),
                        List.of("alertmanager")),
                new ToolDefinition(
                        "alertmanager.expireSilence",
                        "alertmanager",
                        "Expire an existing maintenance silence",
                        false,
                        true,
                        List.of(TaskType.RESTART_SERVICE, TaskType.SCALE_WORKLOAD, TaskType.PATCH_CONFIG),
                        List.of("silenceId"),
                        List.of("alertmanager")));
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
        metadata.put("targetSystem", "alertmanager");
        metadata.put("silenceId", parameters.getOrDefault("silenceId", ""));
        return toolHttpClient.post(
                properties.getIntegrations().getAlertmanager().getEndpoint(),
                request,
                properties.getIntegrations().getAlertmanager().getTimeoutMillis(),
                metadata);
    }
}
