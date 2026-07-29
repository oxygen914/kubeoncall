package com.kubeoncall.evidence;

import java.time.Instant;

public record EvidenceWindow(Instant start, Instant end) {

    public EvidenceWindow {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("Evidence window start must not be after end");
        }
    }
}
