package com.kubeoncall.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kubeoncall.agent.planner.PlannerToolEvidenceCollector;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;

class EvidenceOrchestratorSkillPolicyTest {

    @Test
    void shouldNotStartPrometheusOrLokiCollectorsOutsideSkillWhitelist() {
        EvidenceScopeResolver scopeResolver = mock(EvidenceScopeResolver.class);
        EvidenceItemFactory factory = mock(EvidenceItemFactory.class);
        PrometheusEvidenceCollector prometheus = mock(PrometheusEvidenceCollector.class);
        LokiQueryClient loki = mock(LokiQueryClient.class);
        KubernetesEvidenceCollector kubernetes = mock(KubernetesEvidenceCollector.class);
        EvidenceConflictDetector conflictDetector = mock(EvidenceConflictDetector.class);
        ConfidenceScorer confidenceScorer = mock(ConfidenceScorer.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setEvidencePrometheusEnabled(true);
        properties.getAiOperations().setEvidenceLokiEnabled(true);
        properties.getAiOperations().setEvidenceK8sResourceStateEnabled(false);
        properties.getAiOperations().setEvidenceK8sEventsEnabled(false);
        properties.getAiOperations().setEvidencePodLogsEnabled(false);
        EvidenceCollectionScope scope = scope();
        EvidenceItem forbidden = forbidden(scope);
        when(scopeResolver.resolve(any(), anyString())).thenReturn(scope);
        when(factory.fromPlannerPayload(any(), anyMap())).thenReturn(List.of());
        when(factory.fromMap(any(), any(), anyMap(), anyString())).thenReturn(forbidden);
        when(conflictDetector.detect(any())).thenReturn(List.of());
        when(confidenceScorer.score(any(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(new ConfidenceAssessment(0, "LOW", Map.of()));
        EvidenceOrchestrator orchestrator = new EvidenceOrchestrator(
                scopeResolver,
                factory,
                prometheus,
                loki,
                kubernetes,
                null,
                conflictDetector,
                confidenceScorer,
                properties);
        GraphState state = new GraphState();
        state.getContext().put("activatedSkillIds", List.of("node-runtime-pressure-triage"));
        state.getContext().put("activatedSkillToolWhitelist", List.of("kubernetes.describeResource"));

        EvidenceOrchestrator.Result result = orchestrator.collect(
                state, "diagnose node", new PlannerToolEvidenceCollector.Evidence("worker-1", Map.of()));

        assertEquals(2, result.items().size());
        verify(prometheus, never()).collect(any());
        verify(loki, never()).queryRange(any());
        verify(kubernetes, never()).collect(any(), any());
        orchestrator.close();
    }

    private static EvidenceCollectionScope scope() {
        Instant now = Instant.now();
        return new EvidenceCollectionScope(
                "exec-1",
                "local",
                "prod",
                "",
                new EvidenceResource("Node", "worker-1", "node-uid"),
                now.minusSeconds(300),
                now);
    }

    private static EvidenceItem forbidden(EvidenceCollectionScope scope) {
        return new EvidenceItem(
                "evd-forbidden",
                scope.executionId(),
                EvidenceType.METRIC,
                "skill-policy",
                scope.cluster(),
                scope.namespace(),
                scope.resource(),
                Instant.now(),
                new EvidenceWindow(scope.start(), scope.end()),
                "",
                "",
                Map.of(),
                0,
                false,
                false,
                "hash-forbidden",
                EvidenceCollectionStatus.FORBIDDEN,
                "SKILL_TOOL_NOT_ALLOWED",
                "",
                Map.of());
    }
}
