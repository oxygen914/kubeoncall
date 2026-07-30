package com.kubeoncall.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kubeoncall.monitoring.MonitoringQueryService;
import com.kubeoncall.monitoring.MonitoringViews;
import com.kubeoncall.service.KubeOnCallMetricsService;

/** Builds bounded metric evidence from the server-owned Prometheus monitoring queries. */
@Component
public class PrometheusEvidenceCollector {

    private final MonitoringQueryService monitoring;
    private final EvidenceScopePolicy scopePolicy;
    private final EvidenceItemFactory factory;
    private final KubeOnCallMetricsService metrics;

    public PrometheusEvidenceCollector(
            MonitoringQueryService monitoring,
            EvidenceScopePolicy scopePolicy,
            EvidenceItemFactory factory,
            KubeOnCallMetricsService metrics) {
        this.monitoring = monitoring;
        this.scopePolicy = scopePolicy;
        this.factory = factory;
        this.metrics = metrics;
    }

    public List<EvidenceItem> collect(EvidenceCollectionScope scope) {
        long startedAt = System.currentTimeMillis();
        var rejection = scopePolicy.rejection(scope);
        if (rejection.isPresent()) {
            return List.of(result(scope, EvidenceCollectionStatus.FORBIDDEN, rejection.get(), null, startedAt));
        }
        try {
            String environment = scope.environment();
            boolean environmentFilterApplied = true;
            if (environment != null && !environment.isBlank()) {
                MonitoringViews.ScopeCatalog catalog = monitoring.scopes(scope.cluster(), environment);
                environmentFilterApplied = catalog.capabilities().environmentFilterAvailable();
                if (!environmentFilterApplied) {
                    environment = null;
                }
            }
            MonitoringViews.Summary summary = monitoring.summary(scope.cluster(), environment, scope.namespace());
            MonitoringViews.PodMemoryTimeline memoryTimeline = null;
            String memoryTimelineStatus = "NOT_APPLICABLE";
            String memoryTimelineErrorType = "";
            if ("pod".equalsIgnoreCase(scope.resource().kind())
                    && !scope.resource().name().isBlank()) {
                try {
                    memoryTimeline = monitoring.podMemoryTimeline(
                            scope.cluster(),
                            environment,
                            scope.namespace(),
                            scope.resource().name(),
                            scope.start(),
                            scope.end());
                    memoryTimelineStatus = memoryTimeline.containers().isEmpty() ? "EMPTY" : "SUCCEEDED";
                } catch (RuntimeException ex) {
                    memoryTimelineStatus = "UNAVAILABLE";
                    memoryTimelineErrorType = "PROMETHEUS_MEMORY_TIMELINE_UNAVAILABLE";
                }
            }
            boolean available = summary.dataSources().nodeMetricsAvailable()
                    || summary.dataSources().kubernetesStateAvailable();
            return List.of(result(
                    scope,
                    available ? EvidenceCollectionStatus.SUCCEEDED : EvidenceCollectionStatus.EMPTY,
                    "",
                    summary,
                    environmentFilterApplied,
                    memoryTimeline,
                    memoryTimelineStatus,
                    memoryTimelineErrorType,
                    startedAt));
        } catch (RuntimeException ex) {
            return List.of(
                    result(scope, EvidenceCollectionStatus.UNAVAILABLE, "PROMETHEUS_UNAVAILABLE", null, startedAt));
        }
    }

    private EvidenceItem result(
            EvidenceCollectionScope scope,
            EvidenceCollectionStatus status,
            String errorType,
            MonitoringViews.Summary summary,
            long startedAt) {
        return result(scope, status, errorType, summary, true, null, "NOT_APPLICABLE", "", startedAt);
    }

    private EvidenceItem result(
            EvidenceCollectionScope scope,
            EvidenceCollectionStatus status,
            String errorType,
            MonitoringViews.Summary summary,
            boolean environmentFilterApplied,
            MonitoringViews.PodMemoryTimeline memoryTimeline,
            String memoryTimelineStatus,
            String memoryTimelineErrorType,
            long startedAt) {
        long latencyMs = Math.max(0, System.currentTimeMillis() - startedAt);
        metrics.recordEvidenceCollection("prometheus", status.name(), latencyMs);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "prometheus");
        value.put("collectionStatus", status.name());
        value.put("errorType", errorType);
        value.put("latencyMs", latencyMs);
        value.put("query", "server-managed monitoring summary");
        value.put("environmentFilterApplied", environmentFilterApplied);
        value.put("memoryTimelineCollectionStatus", memoryTimelineStatus);
        if (!memoryTimelineErrorType.isBlank()) {
            value.put("memoryTimelineErrorType", memoryTimelineErrorType);
        }
        if (memoryTimeline != null) {
            value.put("memoryTimeline", memoryTimeline);
        }
        if (summary != null) {
            value.put("observedAt", summary.collectedAt().toString());
            value.put(
                    "summary",
                    "nodes total=%d ready=%d notReady=%d; pods total=%s phases=%s"
                            .formatted(
                                    summary.totalNodes(),
                                    summary.readyNodes(),
                                    summary.notReadyNodes(),
                                    summary.totalPods() == null ? "unavailable" : summary.totalPods(),
                                    summary.podPhaseCounts()));
            value.put("nodeMetricsAvailable", summary.dataSources().nodeMetricsAvailable());
            value.put("kubernetesStateAvailable", summary.dataSources().kubernetesStateAvailable());
            value.put("totalNodes", summary.totalNodes());
            value.put("readyNodes", summary.readyNodes());
            value.put("notReadyNodes", summary.notReadyNodes());
            value.put("unknownNodes", summary.unknownNodes());
            value.put("totalPods", summary.totalPods());
            value.put("podPhaseCounts", summary.podPhaseCounts());
            value.put("averageCpuUsagePercent", summary.averageCpuUsagePercent());
        }
        return factory.fromMap(scope, EvidenceType.METRIC, value, "prometheus");
    }
}
