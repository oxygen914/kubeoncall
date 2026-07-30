package com.kubeoncall.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.skill.SkillExecutionPolicy;
import com.kubeoncall.tool.ToolDefinition;
import com.kubeoncall.tool.ToolExecutor;

class KubernetesEvidenceCollectorTest {

    @Test
    void shouldCollectResourceEventsAndCurrentPreviousLogs() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setEvidenceK8sResourceStateEnabled(true);
        properties.getAiOperations().setEvidenceK8sEventsEnabled(true);
        properties.getAiOperations().setEvidencePodLogsEnabled(true);
        EvidenceScopePolicy scopePolicy = mock(EvidenceScopePolicy.class);
        when(scopePolicy.rejection(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        ToolExecutor kubernetes = new StubKubernetesExecutor();
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(
                List.of(kubernetes),
                properties,
                scopePolicy,
                new EvidenceItemFactory(new ObjectMapper(), properties),
                mock(KubeOnCallMetricsService.class));
        EvidenceCollectionScope scope = new EvidenceCollectionScope(
                "exe_test",
                "local",
                "local",
                "kubeoncall-system",
                new EvidenceResource("Pod", "adapter-abc", "pod-uid"),
                Instant.now().minusSeconds(300),
                Instant.now());

        List<EvidenceItem> items = collector.collect(scope);

        assertEquals(4, items.size());
        assertTrue(items.stream().anyMatch(item -> item.type() == EvidenceType.RESOURCE_STATE && item.succeeded()));
        assertTrue(items.stream()
                .filter(item -> item.type() == EvidenceType.RESOURCE_STATE)
                .findFirst()
                .orElseThrow()
                .snippet()
                .contains("\"exitCode\":42"));
        assertTrue(items.stream().anyMatch(item -> item.type() == EvidenceType.K8S_EVENT && item.succeeded()));
        assertEquals(
                2,
                items.stream()
                        .filter(item -> item.type() == EvidenceType.POD_LOG)
                        .count());
        List<EvidenceItem> podLogs = items.stream()
                .filter(item -> item.type() == EvidenceType.POD_LOG)
                .toList();
        assertNotEquals(podLogs.get(0).evidenceId(), podLogs.get(1).evidenceId());
        assertNotEquals(podLogs.get(0).contentHash(), podLogs.get(1).contentHash());
        assertEquals(false, podLogs.get(0).metadata().get("previous"));
        assertEquals(true, podLogs.get(1).metadata().get("previous"));
    }

    @Test
    void shouldMapHttp403ToForbiddenEvidence() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setEvidenceK8sEventsEnabled(true);
        EvidenceScopePolicy scopePolicy = mock(EvidenceScopePolicy.class);
        when(scopePolicy.rejection(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        ToolExecutor forbidden = new StubKubernetesExecutor() {
            @Override
            public Map<String, Object> execute(String action, Map<String, Object> parameters) {
                return Map.of("status", "failed", "httpStatus", 403, "errorType", "HttpStatusError");
            }
        };
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(
                List.of(forbidden),
                properties,
                scopePolicy,
                new EvidenceItemFactory(new ObjectMapper(), properties),
                mock(KubeOnCallMetricsService.class));
        EvidenceCollectionScope scope = new EvidenceCollectionScope(
                "exe_test",
                "local",
                "local",
                "kubeoncall-system",
                new EvidenceResource("Pod", "adapter-abc", "pod-uid"),
                Instant.now().minusSeconds(300),
                Instant.now());

        assertEquals(
                EvidenceCollectionStatus.FORBIDDEN,
                collector.collect(scope).get(0).collectionStatus());
    }

    @Test
    void shouldCollectOnlyKubernetesEvidenceAllowedByTheSkill() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setEvidenceK8sResourceStateEnabled(true);
        properties.getAiOperations().setEvidenceK8sEventsEnabled(true);
        properties.getAiOperations().setEvidencePodLogsEnabled(true);
        EvidenceScopePolicy scopePolicy = mock(EvidenceScopePolicy.class);
        when(scopePolicy.rejection(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        ToolExecutor kubernetes = mock(ToolExecutor.class);
        when(kubernetes.getExecutorKind()).thenReturn("kubernetes");
        when(kubernetes.execute(eq("describeResource"), anyMap()))
                .thenReturn(
                        Map.of("status", "success", "httpStatus", 200, "response", Map.of("summary", "Node Ready")));
        KubernetesEvidenceCollector collector = new KubernetesEvidenceCollector(
                List.of(kubernetes),
                properties,
                scopePolicy,
                new EvidenceItemFactory(new ObjectMapper(), properties),
                mock(KubeOnCallMetricsService.class));
        EvidenceCollectionScope scope = new EvidenceCollectionScope(
                "exe_test",
                "local",
                "prod",
                "",
                new EvidenceResource("Node", "worker-1", "node-uid"),
                Instant.now().minusSeconds(300),
                Instant.now());

        List<EvidenceItem> items = collector.collect(
                scope, SkillExecutionPolicy.ToolAccess.restricted(List.of("kubernetes.describeResource")));

        assertEquals(3, items.size());
        assertTrue(items.stream().anyMatch(item -> item.type() == EvidenceType.RESOURCE_STATE && item.succeeded()));
        assertTrue(items.stream()
                .filter(item -> item.type() != EvidenceType.RESOURCE_STATE)
                .allMatch(item -> item.collectionStatus() == EvidenceCollectionStatus.FORBIDDEN
                        && "SKILL_TOOL_NOT_ALLOWED".equals(item.errorType())));
        verify(kubernetes).execute(eq("describeResource"), anyMap());
        verify(kubernetes, never()).execute(eq("queryEvents"), anyMap());
        verify(kubernetes, never()).execute(eq("queryPodLogs"), anyMap());
    }

    private static class StubKubernetesExecutor implements ToolExecutor {

        @Override
        public String getExecutorKind() {
            return "kubernetes";
        }

        @Override
        public List<ToolDefinition> supportedTools() {
            return List.of();
        }

        @Override
        public Map<String, Object> execute(String action, Map<String, Object> parameters) {
            Object response =
                    switch (action) {
                        case "describeResource" ->
                            Map.of(
                                    "summary",
                                    "Pod is Running",
                                    "phase",
                                    "Running",
                                    "containers",
                                    List.of(Map.of(
                                            "name",
                                            "adapter",
                                            "ready",
                                            false,
                                            "lastState",
                                            Map.of("reason", "Error", "exitCode", 42))),
                                    "resource",
                                    Map.of("kind", "Pod", "name", "adapter-abc", "uid", "pod-uid"));
                        case "queryEvents" ->
                            Map.of(
                                    "items",
                                    List.of(Map.of(
                                            "summary",
                                            "Started container",
                                            "resource",
                                            Map.of("kind", "Pod", "name", "adapter-abc", "uid", "pod-uid"))));
                        case "queryPodLogs" ->
                            Map.of(
                                    "items",
                                    List.of(Map.of(
                                            "summary",
                                            Boolean.TRUE.equals(parameters.get("previous"))
                                                    ? "previous logs"
                                                    : "current logs",
                                            "snippet",
                                            "adapter ready",
                                            "resource",
                                            Map.of("kind", "Pod", "name", "adapter-abc", "uid", "pod-uid"))));
                        default -> Map.of();
                    };
            return Map.of("status", "success", "httpStatus", 200, "response", response);
        }
    }
}
