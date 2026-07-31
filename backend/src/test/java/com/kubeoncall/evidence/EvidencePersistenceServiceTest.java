package com.kubeoncall.evidence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.domain.graph.GraphState;

class EvidencePersistenceServiceTest {

    @Test
    void restoresTypedFactsFromCheckpointMapsBeforePersisting() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
        ConclusionRepository conclusionRepository = mock(ConclusionRepository.class);
        EvidencePersistenceService service = new EvidencePersistenceService(
                provider(evidenceRepository), provider(conclusionRepository), objectMapper);
        EvidenceItem evidence = evidence();
        AiConclusion conclusion = conclusion();
        GraphState state = new GraphState();
        state.setExecutionId("exe_1");
        state.getContext().put("evidenceItems", List.of(objectMapper.convertValue(evidence, Map.class)));
        state.getContext().put("conclusions", List.of(objectMapper.convertValue(conclusion, Map.class)));

        service.persist(state);

        verify(evidenceRepository).upsert("exe_1", evidence);
        verify(conclusionRepository).upsert("exe_1", conclusion);
    }

    private static EvidenceItem evidence() {
        Instant now = Instant.parse("2026-07-29T10:00:00Z");
        return new EvidenceItem(
                "evd_1",
                "exe_1",
                EvidenceType.K8S_EVENT,
                "kubernetes-api",
                "test-01",
                "payments",
                new EvidenceResource("Pod", "payment-api", "uid-1"),
                now,
                new EvidenceWindow(now.minusSeconds(60), now),
                "FailedScheduling",
                "Insufficient memory",
                Map.of("sequence", "10"),
                5,
                true,
                false,
                "sha256:1",
                EvidenceCollectionStatus.SUCCEEDED,
                "",
                "",
                Map.of("reason", "FailedScheduling"));
    }

    private static AiConclusion conclusion() {
        return new AiConclusion(
                "con_1",
                "exe_1",
                "Pod cannot be scheduled",
                "P2",
                "SUPPORTED",
                List.of("evd_1"),
                List.of(new SopEvidenceReference("pending-triage", "1.0.0", "runbook", "Scheduling")),
                new ConfidenceAssessment(0.8, "HIGH", Map.of("directEvidence", 1.0)),
                Map.of("mode", "REAL_MODEL"),
                new RecommendedAction("PATCH_CONFIG", true, Map.of("resource", "payment-api")));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.getObject()).thenReturn(value);
        return provider;
    }
}
