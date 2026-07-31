package com.kubeoncall.tool;

import java.util.List;
import java.util.Map;

import com.kubeoncall.domain.task.TaskType;

public record ToolDefinition(
        String name,
        String executorKind,
        String description,
        boolean readOnly,
        boolean requiresApproval,
        List<TaskType> supportedTaskTypes,
        List<String> requiredParameters,
        List<String> targetSystems,
        Map<String, Object> inputSchema) {

    public ToolDefinition(
            String name,
            String executorKind,
            String description,
            boolean readOnly,
            boolean requiresApproval,
            List<TaskType> supportedTaskTypes,
            List<String> requiredParameters,
            List<String> targetSystems) {
        this(
                name,
                executorKind,
                description,
                readOnly,
                requiresApproval,
                supportedTaskTypes,
                requiredParameters,
                targetSystems,
                Map.of());
    }

    public ToolDefinition {
        supportedTaskTypes = supportedTaskTypes == null ? List.of() : List.copyOf(supportedTaskTypes);
        requiredParameters = requiredParameters == null ? List.of() : List.copyOf(requiredParameters);
        targetSystems = targetSystems == null ? List.of() : List.copyOf(targetSystems);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
