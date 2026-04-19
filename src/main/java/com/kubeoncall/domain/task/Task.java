package com.kubeoncall.domain.task;

import java.util.Map;

public record Task(
        String taskId,
        String description,
        TaskType taskType,
        RiskLevel riskLevel,
        String target,
        Map<String, Object> parameters,
        SopReference sopReference
) {
}
