package com.kubeoncall.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MonitoringViews {

    private MonitoringViews() {}

    public record DataSources(boolean nodeMetricsAvailable, boolean kubernetesStateAvailable) {}

    public record Cluster(String name, boolean nodeMetricsAvailable, boolean kubernetesStateAvailable, int nodeCount) {}

    public record ClusterList(List<Cluster> clusters, Instant collectedAt) {}

    public record Scope(String cluster, String environment, String namespace) {}

    public record ScopeValue(String value, String label, String cluster, String environment, long resourceCount) {}

    public record ScopeCapabilities(
            boolean clusterFilterAvailable, boolean environmentFilterAvailable, boolean namespaceFilterAvailable) {}

    public record ScopeCatalog(
            List<ScopeValue> clusters,
            List<ScopeValue> environments,
            List<ScopeValue> namespaces,
            ScopeCapabilities capabilities,
            Instant collectedAt) {}

    public record Summary(
            String cluster,
            int totalNodes,
            long readyNodes,
            long notReadyNodes,
            long unknownNodes,
            Long totalPods,
            Map<String, Long> podPhaseCounts,
            Double averageCpuUsagePercent,
            DataSources dataSources,
            Instant collectedAt) {}

    public record Node(
            String name,
            String ready,
            Boolean exporterUp,
            Double cpuUsagePercent,
            Double memoryUsagePercent,
            Long podCount) {}

    public record NodeList(String cluster, DataSources dataSources, List<Node> nodes, Instant collectedAt) {}

    public record Pod(String namespace, String name, String node, String phase, long restartCount) {}

    public record PodList(
            String cluster, boolean kubernetesStateAvailable, List<Pod> pods, int returned, Instant collectedAt) {}

    public record CpuPoint(Instant timestamp, double value) {}

    public record CpuTrend(
            String cluster,
            String node,
            String window,
            boolean nodeMetricsAvailable,
            List<CpuPoint> points,
            Instant collectedAt) {}

    public record HealthPoint(Instant timestamp, double readyPercent, long abnormalPods, double healthScore) {}

    public record HealthComparison(
            Double currentAverage, Double previousAverage, Double delta, String direction, boolean baselineAvailable) {}

    public record HealthTrend(
            Scope scope,
            String window,
            long stepSeconds,
            List<HealthPoint> current,
            List<HealthPoint> previous,
            HealthComparison comparison,
            Instant collectedAt) {}

    public record RelatedChange(
            String changeId,
            String changeType,
            String resourceName,
            String namespace,
            Instant changedAt,
            double score,
            String reason,
            List<String> suggestions) {}

    public record AlarmChangeCorrelation(
            String alarmId,
            String alertName,
            String severity,
            String status,
            String resourceName,
            Instant firstSeen,
            List<RelatedChange> changes) {}

    public record CorrelationFeed(
            Scope scope,
            boolean alarmDataAvailable,
            boolean changeDataAvailable,
            List<AlarmChangeCorrelation> correlations,
            Instant collectedAt) {}

    public record AdviceItem(
            String id,
            String title,
            String risk,
            String summary,
            String evidence,
            String recommendation,
            String source,
            String analysisPath) {}

    public record AdviceFeed(
            Scope scope,
            String generatedBy,
            boolean modelAvailable,
            String safetyMode,
            List<AdviceItem> advice,
            Instant collectedAt) {}
}
