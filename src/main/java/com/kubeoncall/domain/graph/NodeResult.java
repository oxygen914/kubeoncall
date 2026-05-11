package com.kubeoncall.domain.graph;

import java.util.LinkedHashMap;
import java.util.Map;

public record NodeResult(
        String nodeName,
        NodeStatus status,
        String message,
        Map<String, Object> payload
) {

    public String retryReason() {
        if (status != NodeStatus.RETRY || payload == null) {
            return null;
        }
        Object value = payload.get("retryReason");
        return value == null ? null : String.valueOf(value);
    }

    public String retryStrategy() {
        if (status != NodeStatus.RETRY || payload == null) {
            return null;
        }
        Object value = payload.get("retryStrategy");
        return value == null ? null : String.valueOf(value);
    }

    public static NodeResult retry(String nodeName,
                                   String message,
                                   String retryReason,
                                   String retryStrategy,
                                   Map<String, Object> payload) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (payload != null) {
            merged.putAll(payload);
        }
        if (retryReason != null && !retryReason.isBlank()) {
            merged.put("retryReason", retryReason);
        }
        if (retryStrategy != null && !retryStrategy.isBlank()) {
            merged.put("retryStrategy", retryStrategy);
        }
        return new NodeResult(nodeName, NodeStatus.RETRY, message, merged);
    }
}
