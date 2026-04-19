package com.kubeoncall.domain.task;

import java.util.List;
import java.util.Map;

public record PlannerSummary(
        String normalizedRequest,
        String intent,
        String confidence,
        String target,
        String targetSource,
        Map<String, String> parameterSources,
        List<String> missingSignals,
        String summary,
        List<String> consultedTools,
        Map<String, String> evidenceSources
) {
}
