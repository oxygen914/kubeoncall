package com.kubeoncall.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(
        @NotBlank String question,
        String sessionId
) {
}
