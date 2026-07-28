package com.kubeoncall.monitoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MonitoringViews {

    private MonitoringViews() {}

    public record DataSources(boolean nodeMetricsAvailable, boolean kubernetesStateAvailable) {}

    public record Cluster(String name, boolean nodeMetricsAvailable, boolean kubernetesStateAvailable, int nodeCount) {}

    public record ClusterList(List<Cluster> clusters, Instant collectedAt) {}

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
}
