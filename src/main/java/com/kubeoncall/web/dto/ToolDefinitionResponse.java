package com.kubeoncall.web.dto;

import java.util.List;

public record ToolDefinitionResponse(
        String name,
        String executorKind,
        String description,
        boolean readOnly,
        boolean requiresApproval,
        List<String> supportedTaskTypes,
        List<String> requiredParameters,
        List<String> targetSystems
) {
}
