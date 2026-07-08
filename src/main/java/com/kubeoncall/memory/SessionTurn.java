package com.kubeoncall.memory;

import java.time.Instant;

public record SessionTurn(
        String executionId,
        String question,
        String answer,
        String status,
        Instant createdAt
) {
    public SessionTurn {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
