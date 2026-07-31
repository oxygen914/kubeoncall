package com.kubeoncall.domain.graph;

import java.util.List;
import java.util.Map;

public record ExecutionPlan(
        String executorKind,
        String action,
        Map<String, Object> parameters,
        List<String> requiredParameters,
        List<String> missingParameters,
        Map<String, String> parameterSources,
        String executionSummary,
        String retryHint) {}
