package com.kubeoncall.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record KnowledgeIngestRequest(
        @NotBlank String title,
        @NotBlank String content,
        @NotBlank String source,
        Map<String, String> metadata
) {
}
