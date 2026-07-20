package com.kubeoncall.domain.graph;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PauseMetadata(
        String reason,
        String waitingNode,
        Instant pausedAt,
        String taskId,
        String taskType,
        String target,
        String riskLevel,
        String requiredRole,
        List<String> riskReasons,
        Map<String, Object> snapshot) {}
