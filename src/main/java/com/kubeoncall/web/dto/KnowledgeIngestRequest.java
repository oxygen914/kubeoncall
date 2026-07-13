package com.kubeoncall.web.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeIngestRequest(
        @NotBlank String title,
        @NotBlank String content,
        @NotBlank String source,
        Map<String, String> metadata) {}
