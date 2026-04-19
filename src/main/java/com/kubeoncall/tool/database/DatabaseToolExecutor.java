package com.kubeoncall.tool.database;

import com.kubeoncall.domain.task.TaskType;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatabaseToolExecutor implements ToolExecutor {

    @Override
    public String getExecutorKind() {
        return "database";
    }

    @Override
    public List<ToolDefinition> supportedTools() {
        return List.of(
                new ToolDefinition(
                        "database.cleanData",
                        "database",
                        "Execute controlled data cleanup against the configured namespace scope",
                        false,
                        true,
                        List.of(TaskType.CLEAN_DATA),
                        List.of("namespace", "target"),
                        List.of("database")
                )
        );
    }

    @Override
    public Map<String, Object> execute(String action, Map<String, Object> parameters) {
        return Map.of(
                "executor", getExecutorKind(),
                "action", action,
                "parameters", parameters,
                "targetSystem", "database",
                "status", "simulated_success",
                "affectedRows", 42,
                "simulatedLatencyMs", 210
        );
    }
}
