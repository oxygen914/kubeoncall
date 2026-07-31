package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EvidenceConflictDetectorTest {

    private final EvidenceConflictDetector detector = new EvidenceConflictDetector();

    @Test
    void doesNotInterpretNotReadyOrUnavailableAsHealthySubstrings() {
        assertThat(detector.detect(List.of(state("NotReady and unavailable")))).isEmpty();
    }

    @Test
    void reportsContradictoryHealthStatesForTheSameResourceUid() {
        assertThat(detector.detect(List.of(state("Ready"), state("NotReady"))))
                .singleElement()
                .satisfies(conflict -> {
                    assertThat(conflict).containsEntry("type", "RESOURCE_HEALTH_CONFLICT");
                    assertThat(conflict).containsEntry("resource", "uid-1");
                    assertThat(conflict).containsEntry("resolved", false);
                });
    }

    private static EvidenceItem state(String summary) {
        return new EvidenceItem(
                "evd_" + summary,
                "exe_1",
                EvidenceType.RESOURCE_STATE,
                "kubernetes-api",
                "test-01",
                "payments",
                new EvidenceResource("Pod", "payment-api", "uid-1"),
                Instant.now(),
                null,
                summary,
                summary,
                Map.of(),
                5,
                true,
                false,
                "sha256:" + summary,
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of());
    }
}
