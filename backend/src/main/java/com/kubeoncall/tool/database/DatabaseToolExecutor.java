package com.kubeoncall.tool.database;

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
public class DatabaseToolExecutor implements ToolExecutor {

    private final ToolHttpClient toolHttpClient;
    private final KubeOnCallProperties properties;

    public DatabaseToolExecutor(ToolHttpClient toolHttpClient, KubeOnCallProperties properties) {
        this.toolHttpClient = toolHttpClient;
        this.properties = properties;
    }

    @Override
    public String getExecutorKind() {
        return "database";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(new ToolDefinition(
                "database.cleanData",
                "database",
                "Execute controlled data cleanup against the configured namespace scope",
                false,
                true,
                List.of(TaskType.CLEAN_DATA),
                List.of("namespace", "target"),
                List.of("database")));
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
        metadata.put("targetSystem", "database");
        return toolHttpClient.post(
                properties.getIntegrations().getDatabase().getEndpoint(),
                request,
                properties.getIntegrations().getDatabase().getTimeoutMillis(),
                metadata);
    }
}
