package com.kubeoncall.tool;

import com.kubeoncall.domain.task.TaskType;

import java.util.List;

public record ToolDefinition(
        String name,
        String executorKind,
        String description,
        boolean readOnly,
        boolean requiresApproval,
        List<TaskType> supportedTaskTypes,
        List<String> requiredParameters,
        List<String> targetSystems
) {
}
