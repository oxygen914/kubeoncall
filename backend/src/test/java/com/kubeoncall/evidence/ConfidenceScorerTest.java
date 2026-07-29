package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfidenceScorerTest {

    private final ConfidenceScorer scorer = new ConfidenceScorer();

    @Test
    void scoresFreshMultiSourceDirectEvidenceAsHigh() {
        List<EvidenceItem> evidence = List.of(
                item(EvidenceType.RESOURCE_STATE, "kubernetes-api", 10),
                item(EvidenceType.K8S_EVENT, "kubernetes-api", 10),
                item(EvidenceType.METRIC, "prometheus", 20),
                item(EvidenceType.SOP, "knowledge.searchSop", 30));

        ConfidenceAssessment assessment = scorer.score(evidence, true, false, 0);

        assertThat(assessment.score()).isEqualTo(1.0);
        assertThat(assessment.label()).isEqualTo("HIGH");
        assertThat(assessment.basis()).containsEntry("directEvidence", 1.0).containsEntry("sopSupport", 1.0);
    }

    @Test
    void capsUncertainTargetsConflictsAndSimulation() {
        List<EvidenceItem> evidence = List.of(
                item(EvidenceType.RESOURCE_STATE, "kubernetes-api", 10),
                item(EvidenceType.METRIC, "prometheus", 10),
                item(EvidenceType.SOP, "knowledge.searchSop", 10));

        assertThat(scorer.score(evidence, false, false, 0).score()).isLessThanOrEqualTo(0.49);
        assertThat(scorer.score(evidence, true, false, 1).score()).isLessThanOrEqualTo(0.49);
        assertThat(scorer.score(evidence, true, true, 0).score()).isLessThanOrEqualTo(0.30);
    }

    @Test
    void unavailableSourcesReduceTheExplainableScore() {
        EvidenceItem unavailable = new EvidenceItem(
                "evd_missing",
                "exe_1",
                EvidenceType.POD_LOG,
                "loki",
                "test-01",
                "payments",
                new EvidenceResource("Pod", "payment-api", "uid-1"),
                Instant.now(),
                null,
                "Loki unavailable",
                "",
                Map.of(),
                0,
                true,
                false,
                "sha256:missing",
                EvidenceCollectionStatus.UNAVAILABLE,
                "TIMEOUT",
                "",
                Map.of());

        ConfidenceAssessment assessment = scorer.score(
                List.of(item(EvidenceType.RESOURCE_STATE, "kubernetes-api", 10), unavailable), true, false, 0);

        assertThat(assessment.basis()).containsEntry("missingSignalPenalty", 0.05);
        assertThat(assessment.score()).isLessThan(0.80);
    }

    private static EvidenceItem item(EvidenceType type, String source, long freshnessSeconds) {
        return new EvidenceItem(
                "evd_" + type + "_" + source,
                "exe_1",
                type,
                source,
                "test-01",
                "payments",
                new EvidenceResource("Pod", "payment-api", "uid-1"),
                Instant.now(),
                null,
                type + " evidence",
                "evidence",
                Map.of(),
                freshnessSeconds,
                true,
                false,
                "sha256:" + type,
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of());
    }
}
