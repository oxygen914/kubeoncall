package com.kubeoncall.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovalDecisionRequest(
        @NotBlank String decision,
        String comment,
        String decidedBy
) {
}
