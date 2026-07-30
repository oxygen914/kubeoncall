package com.kubeoncall.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.kubeoncall.agent.planner.PlannerToolEvidenceCollector;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.domain.graph.GraphState;

/** Coordinates bounded multi-source collection and publishes a single Evidence v2 view. */
@Component
public class EvidenceOrchestrator {

    private final EvidenceScopeResolver scopeResolver;
    private final EvidenceItemFactory factory;
    private final PrometheusEvidenceCollector prometheus;
    private final LokiQueryClient loki;
    private final KubernetesEvidenceCollector kubernetes;
    private final EvidenceConflictDetector conflictDetector;
    private final ConfidenceScorer confidenceScorer;
    private final KubeOnCallProperties properties;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "evidence-collector");
        thread.setDaemon(true);
        return thread;
    });

    public EvidenceOrchestrator(
            EvidenceScopeResolver scopeResolver,
            EvidenceItemFactory factory,
            PrometheusEvidenceCollector prometheus,
            LokiQueryClient loki,
            KubernetesEvidenceCollector kubernetes,
            EvidenceConflictDetector conflictDetector,
            ConfidenceScorer confidenceScorer,
            KubeOnCallProperties properties) {
        this.scopeResolver = scopeResolver;
        this.factory = factory;
        this.prometheus = prometheus;
        this.loki = loki;
        this.kubernetes = kubernetes;
        this.conflictDetector = conflictDetector;
        this.confidenceScorer = confidenceScorer;
        this.properties = properties;
    }

    public Result collect(GraphState state, String request, PlannerToolEvidenceCollector.Evidence plannerEvidence) {
        EvidenceCollectionScope scope = scopeResolver.resolve(state, plannerEvidence.target());
        List<EvidenceItem> items = new ArrayList<>(factory.fromPlannerPayload(scope, plannerEvidence.payload()));
        List<CompletableFuture<List<EvidenceItem>>> futures = new ArrayList<>();
        if (properties.getAiOperations().isEvidencePrometheusEnabled()) {
            futures.add(CompletableFuture.supplyAsync(() -> prometheus.collect(scope), executor));
        }
        if (properties.getAiOperations().isEvidenceLokiEnabled()) {
            futures.add(CompletableFuture.supplyAsync(() -> collectLoki(scope, request), executor));
        }
        if (properties.getAiOperations().isEvidenceK8sResourceStateEnabled()
                || properties.getAiOperations().isEvidenceK8sEventsEnabled()
                || properties.getAiOperations().isEvidencePodLogsEnabled()) {
            futures.add(CompletableFuture.supplyAsync(() -> kubernetes.collect(scope), executor));
        }
        long deadlineMillis = Math.max(500, properties.getAiOperations().getEvidenceCollectionTimeoutMillis());
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        for (CompletableFuture<List<EvidenceItem>> future : futures) {
            try {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new java.util.concurrent.TimeoutException("Evidence collection deadline exceeded");
                }
                items.addAll(future.get(remainingNanos, TimeUnit.NANOSECONDS));
            } catch (Exception ex) {
                future.cancel(true);
                items.add(factory.unavailable(
                        scope,
                        EvidenceType.RESOURCE_STATE,
                        "evidence-orchestrator",
                        ex instanceof java.util.concurrent.TimeoutException
                                ? "COLLECTION_TIMEOUT"
                                : "COLLECTION_FAILED"));
            }
        }
        reconcileDirectSources(items);
        List<Map<String, Object>> conflicts = conflictDetector.detect(items);
        boolean targetCertain = scope.resource().hasUid();
        boolean simulation = Boolean.TRUE.equals(state.getContext().get("simulation"));
        ConfidenceAssessment confidence = confidenceScorer.score(items, targetCertain, simulation, conflicts.size());
        Result result = new Result(scope, List.copyOf(items), conflicts, confidence);
        state.getContext().put("evidenceScope", scope);
        state.getContext().put("evidenceItems", result.items());
        state.getContext().put("evidenceConflicts", result.conflicts());
        state.getContext().put("evidenceConfidence", result.confidence());
        return result;
    }

    private List<EvidenceItem> collectLoki(EvidenceCollectionScope scope, String request) {
        String workload = workloadLabel(scope);
        String pod = podLabel(scope);
        LokiQueryClient.Result result = loki.queryRange(new LokiQueryClient.Request(
                scope,
                workload,
                pod,
                "",
                diagnosticKeyword(request),
                properties.getAiOperations().getLokiMaxLines()));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "loki");
        value.put("collectionStatus", result.status().name());
        value.put("errorType", result.errorType());
        value.put("latencyMs", result.latencyMs());
        value.put("truncated", result.truncated());
        value.put("query", "server-managed query_range");
        if (!result.entries().isEmpty()) {
            value.put("observedAt", result.entries().get(0).observedAt().toString());
            value.put(
                    "snippet",
                    result.entries().stream()
                            .map(entry -> entry.observedAt() + " " + entry.line())
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse(""));
            Map<String, Object> labels = result.entries().get(0).labels();
            value.put("pod", labels.getOrDefault("pod", pod));
            value.put("container", labels.getOrDefault("container", ""));
            value.put("stream", labels.getOrDefault("stream", ""));
        }
        return List.of(factory.fromMap(scope, EvidenceType.POD_LOG, value, "loki"));
    }

    static String workloadLabel(EvidenceCollectionScope scope) {
        String kind = scope.resource().kind().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "deployment", "statefulset", "daemonset", "job", "cronjob", "replicaset" ->
                scope.resource().name();
            default -> "";
        };
    }

    static String podLabel(EvidenceCollectionScope scope) {
        return "pod".equalsIgnoreCase(scope.resource().kind())
                ? scope.resource().name()
                : "";
    }

    static void reconcileDirectSources(List<EvidenceItem> items) {
        boolean directPrometheusSucceeded = items.stream()
                .anyMatch(item ->
                        item.type() == EvidenceType.METRIC && "prometheus".equals(item.source()) && item.succeeded());
        if (directPrometheusSucceeded) {
            items.removeIf(item -> item.type() == EvidenceType.METRIC
                    && "prometheus.queryRange".equals(item.source())
                    && !item.succeeded());
        }
    }

    private static String diagnosticKeyword(String request) {
        if (request == null) {
            return "";
        }
        String lower = request.toLowerCase(java.util.Locale.ROOT);
        for (String keyword : List.of("CrashLoopBackOff", "OOMKilled", "FailedScheduling", "NodeNotReady", "BackOff")) {
            if (lower.contains(keyword.toLowerCase(java.util.Locale.ROOT))) {
                return keyword;
            }
        }
        return "";
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    public record Result(
            EvidenceCollectionScope scope,
            List<EvidenceItem> items,
            List<Map<String, Object>> conflicts,
            ConfidenceAssessment confidence) {

        public Result {
            items = items == null ? List.of() : List.copyOf(items);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
    }
}
