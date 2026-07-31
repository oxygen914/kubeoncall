package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.domain.task.TaskType;

class EvidenceClaimGrounderTest {

    private final EvidenceClaimGrounder grounder = new EvidenceClaimGrounder();

    @Test
    void replacesContradictoryModelClaimWithCollectedEvidenceStatuses() {
        EvidenceClaimGrounder.GroundedClaim result = grounder.ground(
                TaskType.QUERY_LOGS,
                "Pod/adapter-1",
                "All metrics and resource signals are unavailable.",
                List.of("metricsContext", "resourceSnapshot", "sop"),
                List.of(
                        evidence(
                                EvidenceType.METRIC,
                                "prometheus",
                                EvidenceCollectionStatus.SUCCEEDED,
                                "nodes total=1 ready=1",
                                "",
                                Map.of()),
                        evidence(
                                EvidenceType.RESOURCE_STATE,
                                "kubernetes-api",
                                EvidenceCollectionStatus.SUCCEEDED,
                                "Pod/adapter-1 is Running",
                                "",
                                Map.of()),
                        evidence(
                                EvidenceType.SOP,
                                "knowledge.searchSop",
                                EvidenceCollectionStatus.UNAVAILABLE,
                                "",
                                "DEPENDENCY_UNAVAILABLE",
                                Map.of())));

        assertThat(result.claim())
                .contains("METRIC@prometheus=SUCCEEDED")
                .contains("RESOURCE_STATE@kubernetes-api=SUCCEEDED")
                .contains("SOP@knowledge.searchSop=UNAVAILABLE")
                .contains("does not authorize any mutation")
                .doesNotContain("All metrics and resource signals are unavailable");
        assertThat(result.missingSignals()).containsExactly("sop");
    }

    @Test
    void distinguishesCurrentAndEmptyPreviousLogs() {
        EvidenceClaimGrounder.GroundedClaim result = grounder.ground(
                TaskType.QUERY_LOGS,
                "Pod/adapter-1",
                "model claim",
                List.of("currentLogs", "previousLogs"),
                List.of(
                        evidence(
                                EvidenceType.POD_LOG,
                                "kubernetes-api",
                                EvidenceCollectionStatus.SUCCEEDED,
                                "listening on :8080",
                                "",
                                Map.of("previous", false)),
                        evidence(
                                EvidenceType.POD_LOG,
                                "kubernetes-api",
                                EvidenceCollectionStatus.EMPTY,
                                "",
                                "PREVIOUS_LOG_EMPTY",
                                Map.of())));

        assertThat(result.claim())
                .contains("POD_LOG(current)@kubernetes-api=SUCCEEDED")
                .contains("POD_LOG(previous)@kubernetes-api=EMPTY");
        assertThat(result.missingSignals()).isEmpty();
    }

    @Test
    void retainsModelClaimForMutationTasks() {
        EvidenceClaimGrounder.GroundedClaim result = grounder.ground(
                TaskType.RESTART_SERVICE,
                "deployment/api",
                "Restart after approval",
                List.of(),
                List.of(evidence(
                        EvidenceType.RESOURCE_STATE,
                        "kubernetes-api",
                        EvidenceCollectionStatus.SUCCEEDED,
                        "deployment/api is available",
                        "",
                        Map.of())));

        assertThat(result.claim()).isEqualTo("Restart after approval");
    }

    private EvidenceItem evidence(
            EvidenceType type,
            String source,
            EvidenceCollectionStatus status,
            String snippet,
            String errorType,
            Map<String, Object> metadata) {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        return new EvidenceItem(
                "evd_" + type + "_" + source + "_" + status,
                "exe_1",
                type,
                source,
                "local",
                "kubeoncall-system",
                new EvidenceResource("Pod", "adapter-1", "uid-1"),
                now,
                new EvidenceWindow(now.minusSeconds(60), now),
                snippet,
                snippet,
                Map.of(),
                0,
                true,
                false,
                "sha256:test",
                status,
                errorType,
                "",
                metadata);
    }
}
