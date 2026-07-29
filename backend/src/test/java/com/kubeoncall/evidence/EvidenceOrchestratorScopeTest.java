package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class EvidenceOrchestratorScopeTest {

    @Test
    void unknownResourceKindDoesNotBecomeAnUntrustedLokiWorkloadLabel() {
        EvidenceCollectionScope scope = scope("", "KubeOnCall");

        assertThat(EvidenceOrchestrator.workloadLabel(scope)).isEmpty();
        assertThat(EvidenceOrchestrator.podLabel(scope)).isEmpty();
    }

    @Test
    void typedWorkloadAndPodScopesUseTheirServerManagedSelectors() {
        assertThat(EvidenceOrchestrator.workloadLabel(scope("Deployment", "payments")))
                .isEqualTo("payments");
        assertThat(EvidenceOrchestrator.podLabel(scope("Pod", "payments-abc"))).isEqualTo("payments-abc");
    }

    @Test
    void successfulDirectPrometheusEvidenceSupersedesItsUnavailablePlannerPlaceholder() {
        List<EvidenceItem> items = new ArrayList<>(List.of(
                item("prometheus.queryRange", EvidenceCollectionStatus.UNAVAILABLE),
                item("prometheus", EvidenceCollectionStatus.SUCCEEDED)));

        EvidenceOrchestrator.reconcileDirectSources(items);

        assertThat(items).extracting(EvidenceItem::source).containsExactly("prometheus");
    }

    private static EvidenceCollectionScope scope(String kind, String name) {
        Instant now = Instant.now();
        return new EvidenceCollectionScope(
                "exec_1",
                "local",
                "docker",
                "kubeoncall-system",
                new EvidenceResource(kind, name, ""),
                now.minusSeconds(60),
                now);
    }

    private static EvidenceItem item(String source, EvidenceCollectionStatus status) {
        Instant now = Instant.now();
        return new EvidenceItem(
                "evd_" + source,
                "exec_1",
                EvidenceType.METRIC,
                source,
                "local",
                "kubeoncall-system",
                new EvidenceResource("", "", ""),
                now,
                new EvidenceWindow(now.minusSeconds(60), now),
                "metric evidence",
                "",
                Map.of(),
                0,
                true,
                false,
                "sha256:test",
                status,
                status == EvidenceCollectionStatus.SUCCEEDED ? "" : "DEPENDENCY_UNAVAILABLE",
                "",
                Map.of());
    }
}
