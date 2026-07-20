package com.kubeoncall.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.alarm.AlarmPolicyRepositoryFixtures;
import com.kubeoncall.alarm.domain.AlarmEvaluationResult;
import com.kubeoncall.alarm.domain.AlarmPolicy;
import com.kubeoncall.alarm.domain.AlarmResourceType;
import com.kubeoncall.alarm.domain.AlarmSeverity;
import com.kubeoncall.alarm.domain.NormalizedAlarmEvent;
import com.kubeoncall.alarm.policy.AlarmPolicyEngine;
import com.kubeoncall.alarm.policy.YamlAlarmPolicyRepository;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.alarm.AlarmEvent;
import com.kubeoncall.domain.graph.NodeResult;
import com.kubeoncall.domain.graph.NodeStatus;
import com.kubeoncall.domain.rag.KnowledgeDocument;
import com.kubeoncall.domain.rag.RetrievalResult;
import com.kubeoncall.rag.KnowledgeIngestService;
import com.kubeoncall.workflow.node.KnowledgeRetrieveNode;

class KnowledgeRetrieveNodeTest {

    @Test
    void shouldBindYamlPolicyAndAlarmDimensionsToSopRetrieval() {
        KnowledgeIngestService knowledgeService = mock(KnowledgeIngestService.class);
        KnowledgeDocument sop = sopDocument();
        when(knowledgeService.retrieve(anyString(), anyMap())).thenReturn(result(List.of(sop)));
        KnowledgeRetrieveNode node = new KnowledgeRetrieveNode(knowledgeService, List.of(), new KubeOnCallProperties());

        NodeResult result = node.execute(context());

        assertEquals(NodeStatus.SUCCESS, result.status());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> filters = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeService).retrieve(query.capture(), filters.capture());
        assertTrue(query.getValue().contains("PodOOMKilledP1"));
        assertTrue(query.getValue().contains("runbook-pod-oom"));
        assertEquals(
                Map.of(
                        "document_type", "runbook",
                        "category", "k8s-pod",
                        "alertName", "PodOOMKilledP1",
                        "resourceType", "pod",
                        "service", "payment-service",
                        "runbookId", "runbook-pod-oom",
                        "nodeName", "payment-pod",
                        "severity", "P1"),
                filters.getValue());
        assertEquals("none", result.payload().get("ragFilterFallback"));
        assertEquals(filters.getValue(), result.payload().get("ragFilters"));
        assertTrue(result.payload().get("documents") instanceof List<?> documents
                && documents.size() == 1
                && documents.get(0) instanceof Map<?, ?> document
                && "runbook-pod-oom".equals(document.get("runbookId")));
    }

    @Test
    void shouldFallbackOnlyToRunbookBindingWhenStrictDimensionsMiss() {
        KnowledgeIngestService knowledgeService = mock(KnowledgeIngestService.class);
        when(knowledgeService.retrieve(anyString(), anyMap()))
                .thenReturn(result(List.of()), result(List.of(sopDocument())));
        KnowledgeRetrieveNode node = new KnowledgeRetrieveNode(knowledgeService, List.of(), new KubeOnCallProperties());

        NodeResult result = node.execute(context());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> filters = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeService, org.mockito.Mockito.times(2)).retrieve(anyString(), filters.capture());
        Map<String, String> strict = filters.getAllValues().get(0);
        Map<String, String> fallback = filters.getAllValues().get(1);
        assertTrue(strict.containsKey("service"));
        assertEquals(
                Map.of(
                        "document_type", "runbook",
                        "category", "k8s-pod",
                        "runbookId", "runbook-pod-oom"),
                fallback);
        assertFalse(fallback.containsKey("alertName"));
        assertEquals("runbook_binding", result.payload().get("ragFilterFallback"));
        assertEquals(fallback, result.payload().get("ragFilters"));
        assertTrue(result.payload().get("ragRetrievalAttempts") instanceof List<?> attempts && attempts.size() == 2);
    }

    @Test
    void runtimeAlarmDimensionsShouldOverrideConflictingPolicyFilters() {
        KnowledgeIngestService knowledgeService = mock(KnowledgeIngestService.class);
        when(knowledgeService.retrieve(anyString(), anyMap())).thenReturn(result(List.of(sopDocument())));
        KnowledgeRetrieveNode node = new KnowledgeRetrieveNode(knowledgeService, List.of(), new KubeOnCallProperties());

        node.execute(withPolicyFilters(
                context(),
                Map.of(
                        "service", "wrong-service",
                        "runbookId", "wrong-runbook",
                        "tenant", "operations")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> filters = ArgumentCaptor.forClass(Map.class);
        verify(knowledgeService).retrieve(anyString(), filters.capture());
        assertEquals("payment-service", filters.getValue().get("service"));
        assertEquals("runbook-pod-oom", filters.getValue().get("runbookId"));
        assertEquals("operations", filters.getValue().get("tenant"));
    }

    private static AlertWorkflowContext context() {
        NormalizedAlarmEvent event = new NormalizedAlarmEvent(
                "alarm-oom",
                "fp-oom",
                "PodOOMKilledP1",
                "prometheus",
                "warning",
                AlarmSeverity.P1,
                AlarmResourceType.POD,
                "payment-pod",
                "cluster-a",
                "prod",
                "payment-service",
                "kube.pod.oom_killed",
                null,
                null,
                null,
                "1m",
                Map.of(),
                Map.of(),
                "runbook-pod-oom",
                null,
                Instant.now(),
                "payment pod was OOMKilled",
                Map.of());
        YamlAlarmPolicyRepository repository =
                AlarmPolicyRepositoryFixtures.loadFromClasspath("alarm-policies.yml", AlarmSeverity.P3);
        AlarmEvaluationResult evaluation = new AlarmPolicyEngine(repository).evaluate(event);
        assertTrue(evaluation.matched());
        AlarmEvent legacy = new AlarmEvent(
                event.alarmId(),
                event.fingerprint(),
                event.source(),
                evaluation.finalSeverity().name(),
                event.resourceName(),
                event.summary(),
                event.occurredAt(),
                Map.of("namespace", event.namespace()));
        return new AlertWorkflowContext(legacy, event, evaluation, Instant.now());
    }

    private static RetrievalResult result(List<KnowledgeDocument> documents) {
        return new RetrievalResult(
                "oom",
                documents,
                "RAG",
                documents.isEmpty() ? "No matching knowledge found" : "Retrieved SOP",
                List.of("test retrieval"),
                Map.of("resultCount", documents.size()));
    }

    private static AlertWorkflowContext withPolicyFilters(
            AlertWorkflowContext context, Map<String, String> ragFilters) {
        AlarmEvaluationResult current = context.getEvaluationResult();
        AlarmPolicy policy = current.matchedPolicy();
        AlarmPolicy updatedPolicy = new AlarmPolicy(
                policy.id(),
                policy.name(),
                policy.category(),
                policy.metricName(),
                policy.resourceType(),
                policy.severity(),
                policy.condition(),
                policy.promql(),
                policy.window(),
                policy.recover(),
                policy.runbookId(),
                policy.owner(),
                policy.actions(),
                policy.labels(),
                ragFilters);
        AlarmEvaluationResult evaluation = new AlarmEvaluationResult(
                current.matched(),
                updatedPolicy,
                current.policyId(),
                current.finalSeverity(),
                current.threshold(),
                current.runbookId(),
                current.promql(),
                current.window(),
                current.workflowTemplate(),
                current.reason(),
                current.notes());
        return new AlertWorkflowContext(
                context.getAlarmEvent(), context.getNormalizedAlarm(), evaluation, context.getStartedAt());
    }

    private static KnowledgeDocument sopDocument() {
        return new KnowledgeDocument(
                "sop-oom",
                "Pod OOM runbook",
                "Inspect limits and current memory evidence",
                "runbook",
                Map.of(
                        "document_type", "runbook",
                        "category", "k8s-pod",
                        "alertName", "PodOOMKilledP1",
                        "resourceType", "pod",
                        "service", "payment-service",
                        "runbookId", "runbook-pod-oom",
                        "chunk_enable", "true"),
                Instant.parse("2026-07-10T00:00:00Z"));
    }
}
