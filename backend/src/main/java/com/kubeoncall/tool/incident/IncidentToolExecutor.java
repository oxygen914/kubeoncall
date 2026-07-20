package com.kubeoncall.tool.incident;

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
public class IncidentToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public IncidentToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public String getExecutorKind() {
        return "incident";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        List<TaskType> alarmTaskTypes = List.of(TaskType.QUERY_LOGS, TaskType.QUERY_METRICS);
        return List.of(
                new ToolDefinition(
                        "incident.createOrUpdateIncident",
                        "incident",
                        "Create or update a high-priority incident",
                        false,
                        false,
                        alarmTaskTypes,
                        List.of("fingerprint", "severity", "summary"),
                        List.of("incident")),
                new ToolDefinition(
                        "incident.createTicket",
                        "incident",
                        "Create or update a normal operations ticket",
                        false,
                        false,
                        alarmTaskTypes,
                        List.of("fingerprint", "severity", "summary"),
                        List.of("incident")),
                new ToolDefinition(
                        "incident.escalateIncident",
                        "incident",
                        "Escalate an existing incident to the next on-call level",
                        false,
                        false,
                        alarmTaskTypes,
                        List.of("fingerprint", "severity"),
                        List.of("incident")),
                new ToolDefinition(
                        "incident.resolveIncident",
                        "incident",
                        "Resolve the incident associated with a recovered alarm",
                        false,
                        false,
                        alarmTaskTypes,
                        List.of("fingerprint", "resolution"),
                        List.of("incident")),
                new ToolDefinition(
                        "incident.createPostmortem",
                        "incident",
                        "Create a postmortem task for a recovered P0/P1 incident",
                        false,
                        false,
                        alarmTaskTypes,
                        List.of("fingerprint", "severity", "resolution"),
                        List.of("incident")));
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
        metadata.put("targetSystem", "incident");
        metadata.put("fingerprint", parameters.getOrDefault("fingerprint", ""));
        return toolHttpClient.post(
                properties.getIntegrations().getIncident().getEndpoint(),
                request,
                properties.getIntegrations().getIncident().getTimeoutMillis(),
                metadata);
    }
}
