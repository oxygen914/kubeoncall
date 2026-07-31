package com.kubeoncall.rag.runbook;

import java.util.Map;

public record RunbookAsset(
        String runbookId, String title, String content, String resourceName, Map<String, String> metadata) {
    public RunbookAsset {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
