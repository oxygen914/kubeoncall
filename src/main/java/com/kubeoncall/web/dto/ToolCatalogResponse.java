package com.kubeoncall.web.dto;

import java.util.List;

import com.kubeoncall.tool.VerifierCapability;

public record ToolCatalogResponse(
        List<ToolDefinitionResponse> planner,
        List<ToolDefinitionResponse> executor,
        List<VerifierCapability> verifier) {
    public ToolCatalogResponse {
        planner = planner == null ? List.of() : List.copyOf(planner);
        executor = executor == null ? List.of() : List.copyOf(executor);
        verifier = verifier == null ? List.of() : List.copyOf(verifier);
    }
}
