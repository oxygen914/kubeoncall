package com.kubeoncall.domain.task;

public record SopReference(
        String sopId,
        String title,
        String version,
        String source
) {
}
