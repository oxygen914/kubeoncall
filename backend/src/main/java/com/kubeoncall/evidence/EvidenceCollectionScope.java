package com.kubeoncall.evidence;

import java.time.Instant;

/** Bounded target and time window resolved from the authenticated Ask request and current state. */
public record EvidenceCollectionScope(
        String executionId,
        String cluster,
        String environment,
        String namespace,
        EvidenceResource resource,
        Instant start,
        Instant end) {

    public EvidenceCollectionScope {
        executionId = safe(executionId);
        cluster = safe(cluster);
        environment = safe(environment);
        namespace = safe(namespace);
        resource = resource == null ? new EvidenceResource("", "", "") : resource;
        end = end == null ? Instant.now() : end;
        start = start == null ? end.minusSeconds(1800) : start;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Evidence scope start must not be after end");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
