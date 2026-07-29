package com.kubeoncall.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, source-attributed evidence used by planning, verification, UI and audit. */
public record EvidenceItem(
        String evidenceId,
        String executionId,
        EvidenceType type,
        String source,
        String cluster,
        String namespace,
        EvidenceResource resource,
        Instant observedAt,
        EvidenceWindow window,
        String summary,
        String snippet,
        Map<String, Object> locator,
        long freshnessSeconds,
        boolean redacted,
        boolean truncated,
        String contentHash,
        EvidenceCollectionStatus collectionStatus,
        String errorType,
        String artifactReference,
        Map<String, Object> metadata) {

    public EvidenceItem {
        evidenceId = safe(evidenceId);
        executionId = safe(executionId);
        source = safe(source);
        cluster = safe(cluster);
        namespace = safe(namespace);
        resource = resource == null ? new EvidenceResource("", "", "") : resource;
        observedAt = observedAt == null ? Instant.now() : observedAt;
        summary = safe(summary);
        snippet = safe(snippet);
        locator = immutable(locator);
        freshnessSeconds = Math.max(0, freshnessSeconds);
        contentHash = safe(contentHash);
        collectionStatus = collectionStatus == null ? EvidenceCollectionStatus.FAILED : collectionStatus;
        errorType = safe(errorType);
        artifactReference = safe(artifactReference);
        metadata = immutable(metadata);
    }

    public boolean succeeded() {
        return collectionStatus == EvidenceCollectionStatus.SUCCEEDED;
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
