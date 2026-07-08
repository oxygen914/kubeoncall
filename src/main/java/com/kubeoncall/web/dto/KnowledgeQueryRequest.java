package com.kubeoncall.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record KnowledgeQueryRequest(
        @NotBlank String question,
        Map<String, String> filters,
        Integer topK,
        String retrieveMethod,
        Boolean includeTrace
) {
}
