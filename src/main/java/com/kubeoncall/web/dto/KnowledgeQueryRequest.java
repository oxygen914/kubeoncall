package com.kubeoncall.web.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeQueryRequest(
        @NotBlank String question,
        Map<String, String> filters,
        Integer topK,
        String retrieveMethod,
        Boolean includeTrace) {}
