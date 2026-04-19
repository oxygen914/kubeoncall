package com.kubeoncall.domain.graph;

import java.util.Map;

public record NodeResult(
        String nodeName,
        NodeStatus status,
        String message,
        Map<String, Object> payload
) {
}
