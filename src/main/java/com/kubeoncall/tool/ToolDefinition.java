package com.kubeoncall.tool;

import java.util.List;

import com.kubeoncall.domain.task.TaskType;

public record ToolDefinition(
        String name,
        String executorKind,
        String description,
        boolean readOnly,
        boolean requiresApproval,
        List<TaskType> supportedTaskTypes,
        List<String> requiredParameters,
        List<String> targetSystems) {}
