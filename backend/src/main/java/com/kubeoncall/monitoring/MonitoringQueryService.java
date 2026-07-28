package com.kubeoncall.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.kubeoncall.monitoring.MonitoringViews.Cluster;
import com.kubeoncall.monitoring.MonitoringViews.ClusterList;
import com.kubeoncall.monitoring.MonitoringViews.CpuPoint;
import com.kubeoncall.monitoring.MonitoringViews.CpuTrend;
import com.kubeoncall.monitoring.MonitoringViews.DataSources;
import com.kubeoncall.monitoring.MonitoringViews.Node;
import com.kubeoncall.monitoring.MonitoringViews.NodeList;
import com.kubeoncall.monitoring.MonitoringViews.Pod;
import com.kubeoncall.monitoring.MonitoringViews.PodList;
import com.kubeoncall.monitoring.MonitoringViews.Summary;
import com.kubeoncall.monitoring.PrometheusReadClient.InstantSample;

@Service
public class MonitoringQueryService {

    private static final String EXPORTER_UP = "max by (cluster, node) (up{job=\"node-exporter\"})";
    private static final String NODE_READY =
            "max by (cluster, node) (kube_node_status_condition{condition=\"Ready\",status=\"true\"})";
    private static final String NODE_CPU =
            "100 - (avg by (cluster, node) (rate(node_cpu_seconds_total{job=\"node-exporter\",mode=\"idle\"}[5m])) * 100)";
    private static final String NODE_MEMORY =
            "(1 - (node_memory_MemAvailable_bytes{job=\"node-exporter\"} / node_memory_MemTotal_bytes{job=\"node-exporter\"})) * 100";
    private static final String NODE_PODS = "count by (cluster, node) (kube_pod_info)";
    private static final String KSM_AVAILABLE = "max by (cluster) (kube_node_info)";
    private static final String POD_PHASES = "max by (cluster, namespace, pod, phase) (kube_pod_status_phase == 1)";
    private static final String POD_PHASE_COUNTS =
            "sum by (cluster, phase) (max by (cluster, namespace, pod, phase) (kube_pod_status_phase == 1))";
    private static final String POD_RESTARTS =
            "sum by (cluster, namespace, pod) (kube_pod_container_status_restarts_total)";
    private static final String POD_INFO = "max by (cluster, namespace, pod, node) (kube_pod_info)";

    private final PrometheusReadClient prometheus;

    public MonitoringQueryService(PrometheusReadClient prometheus) {
        this.prometheus = prometheus;
    }

    public ClusterList clusters() {
        List<InstantSample> exporters = prometheus.instant(EXPORTER_UP);
        List<InstantSample> readiness = prometheus.instant(NODE_READY);
        List<InstantSample> ksm = prometheus.instant(KSM_AVAILABLE);
        Set<String> names = new LinkedHashSet<>();
        exporters.stream()
                .map(sample -> sample.labels().get("cluster"))
                .filter(MonitoringQueryService::hasText)
                .forEach(names::add);
        readiness.stream()
                .map(sample -> sample.labels().get("cluster"))
                .filter(MonitoringQueryService::hasText)
                .forEach(names::add);
        ksm.stream()
                .map(sample -> sample.labels().get("cluster"))
                .filter(MonitoringQueryService::hasText)
                .forEach(names::add);

        List<Cluster> clusters = names.stream()
                .sorted()
                .map(name -> new Cluster(
                        name,
                        containsCluster(exporters, name),
                        containsCluster(ksm, name),
                        nodeNames(exporters, readiness, name).size()))
                .toList();
        return new ClusterList(clusters, Instant.now());
    }

    public Summary summary(String cluster) {
        NodeList nodes = nodes(cluster);
        List<InstantSample> ksm = prometheus.instant(filter(KSM_AVAILABLE, "kube_node_info", cluster));
        List<InstantSample> podPhaseSamples =
                prometheus.instant(filter(POD_PHASE_COUNTS, "kube_pod_status_phase", cluster));
        long ready = nodes.nodes().stream()
                .filter(node -> "READY".equals(node.ready()))
                .count();
        long notReady = nodes.nodes().stream()
                .filter(node -> "NOT_READY".equals(node.ready()))
                .count();
        long unknown = nodes.nodes().stream()
                .filter(node -> "UNKNOWN".equals(node.ready()))
                .count();
        Map<String, Long> phases = podPhaseSamples.stream()
                .filter(sample -> hasText(sample.labels().get("phase")))
                .collect(Collectors.toMap(
                        sample -> sample.labels().get("phase"),
                        sample -> Math.round(sample.value()),
                        Long::sum,
                        LinkedHashMap::new));
        boolean kubernetesStateAvailable = !ksm.isEmpty();
        Double averageCpu = nodes.nodes().stream()
                .map(Node::cpuUsagePercent)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .stream()
                .boxed()
                .findFirst()
                .map(MonitoringQueryService::percent)
                .orElse(null);
        return new Summary(
                cluster,
                nodes.nodes().size(),
                ready,
                notReady,
                unknown,
                kubernetesStateAvailable
                        ? phases.values().stream().mapToLong(Long::longValue).sum()
                        : null,
                Map.copyOf(phases),
                averageCpu,
                new DataSources(nodes.dataSources().nodeMetricsAvailable(), kubernetesStateAvailable),
                Instant.now());
    }

    public NodeList nodes(String cluster) {
        List<InstantSample> exporters = prometheus.instant(filter(EXPORTER_UP, "up", cluster));
        List<InstantSample> readiness = prometheus.instant(filter(NODE_READY, "kube_node_status_condition", cluster));
        List<InstantSample> ksm = prometheus.instant(filter(KSM_AVAILABLE, "kube_node_info", cluster));
        List<InstantSample> cpu = prometheus.instant(filter(NODE_CPU, "node_cpu_seconds_total", cluster));
        List<InstantSample> memory = prometheus.instant(filter(NODE_MEMORY, "node_memory_MemAvailable_bytes", cluster));
        List<InstantSample> podCounts = prometheus.instant(filter(NODE_PODS, "kube_pod_info", cluster));

        Set<String> names = nodeNames(exporters, readiness, cluster);
        samplesByNode(cpu).keySet().forEach(names::add);
        samplesByNode(memory).keySet().forEach(names::add);
        samplesByNode(podCounts).keySet().forEach(names::add);

        Map<String, InstantSample> exporterByNode = samplesByNode(exporters);
        Map<String, InstantSample> readyByNode = samplesByNode(readiness);
        Map<String, InstantSample> cpuByNode = samplesByNode(cpu);
        Map<String, InstantSample> memoryByNode = samplesByNode(memory);
        Map<String, InstantSample> podsByNode = samplesByNode(podCounts);

        List<Node> nodes = names.stream()
                .sorted()
                .map(name -> new Node(
                        name,
                        readyState(readyByNode.get(name)),
                        booleanValue(exporterByNode.get(name)),
                        value(cpuByNode.get(name), true),
                        value(memoryByNode.get(name), true),
                        longValue(podsByNode.get(name))))
                .toList();
        return new NodeList(
                cluster, new DataSources(!exporters.isEmpty() || !cpu.isEmpty(), !ksm.isEmpty()), nodes, Instant.now());
    }

    public PodList pods(String cluster, String namespace, String phase, int limit) {
        List<InstantSample> phases = prometheus.instant(filter(POD_PHASES, "kube_pod_status_phase", cluster));
        List<InstantSample> ksm = prometheus.instant(filter(KSM_AVAILABLE, "kube_node_info", cluster));
        List<InstantSample> restarts =
                prometheus.instant(filter(POD_RESTARTS, "kube_pod_container_status_restarts_total", cluster));
        List<InstantSample> podInfo = prometheus.instant(filter(POD_INFO, "kube_pod_info", cluster));
        Map<String, InstantSample> restartByPod = restarts.stream()
                .collect(Collectors.toMap(
                        MonitoringQueryService::podKey,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));
        Map<String, String> nodeByPod = podInfo.stream()
                .filter(sample -> hasText(sample.labels().get("node")))
                .collect(Collectors.toMap(
                        MonitoringQueryService::podKey,
                        sample -> sample.labels().get("node"),
                        (left, right) -> right,
                        LinkedHashMap::new));

        String normalizedPhase = phase == null ? null : phase.trim().toUpperCase(Locale.ROOT);
        List<Pod> pods = phases.stream()
                .filter(sample ->
                        namespace == null || namespace.equals(sample.labels().get("namespace")))
                .filter(sample -> normalizedPhase == null
                        || normalizedPhase.equals(
                                String.valueOf(sample.labels().get("phase")).toUpperCase(Locale.ROOT)))
                .map(sample -> new Pod(
                        sample.labels().getOrDefault("namespace", ""),
                        sample.labels().getOrDefault("pod", ""),
                        nodeByPod.getOrDefault(podKey(sample), ""),
                        sample.labels().getOrDefault("phase", "Unknown"),
                        longValue(restartByPod.get(podKey(sample))) == null
                                ? 0
                                : Objects.requireNonNull(longValue(restartByPod.get(podKey(sample))))))
                .filter(pod -> hasText(pod.name()))
                .sorted(Comparator.comparingInt((Pod pod) -> phaseRank(pod.phase()))
                        .thenComparing(Pod::namespace)
                        .thenComparing(Pod::name))
                .limit(Math.max(1, Math.min(500, limit)))
                .toList();
        return new PodList(cluster, !ksm.isEmpty(), pods, pods.size(), Instant.now());
    }

    public CpuTrend cpuTrend(String cluster, String node, CpuWindow window) {
        String query = filter(NODE_CPU, "node_cpu_seconds_total", cluster);
        Instant end = Instant.now();
        List<CpuPoint> points = prometheus.range(query, end.minus(window.duration()), end, window.step()).stream()
                .filter(series -> node.equals(series.labels().get("node")))
                .findFirst()
                .map(series -> series.points().stream()
                        .map(point -> new CpuPoint(point.timestamp(), percent(point.value())))
                        .toList())
                .orElseGet(List::of);
        return new CpuTrend(cluster, node, window.value(), !points.isEmpty(), points, Instant.now());
    }

    private static String filter(String query, String metric, String cluster) {
        String selector = "cluster=\"" + escapeLabel(cluster) + "\"";
        String withSelector = metric + "{";
        int existing = query.indexOf(withSelector);
        if (existing >= 0) {
            return query.substring(0, existing + withSelector.length())
                    + selector
                    + ","
                    + query.substring(existing + withSelector.length());
        }
        return query.replace(metric, metric + "{" + selector + "}");
    }

    private static Set<String> nodeNames(List<InstantSample> exporters, List<InstantSample> readiness, String cluster) {
        Set<String> names = new LinkedHashSet<>();
        List.of(exporters, readiness).forEach(samples -> samples.stream()
                .filter(sample -> cluster.equals(sample.labels().get("cluster")))
                .map(sample -> sample.labels().get("node"))
                .filter(MonitoringQueryService::hasText)
                .forEach(names::add));
        return names;
    }

    private static Map<String, InstantSample> samplesByNode(List<InstantSample> samples) {
        return samples.stream()
                .filter(sample -> hasText(sample.labels().get("node")))
                .collect(Collectors.toMap(
                        sample -> sample.labels().get("node"),
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private static boolean containsCluster(List<InstantSample> samples, String cluster) {
        return samples.stream()
                .anyMatch(sample -> cluster.equals(sample.labels().get("cluster")));
    }

    private static String readyState(InstantSample sample) {
        if (sample == null) {
            return "UNKNOWN";
        }
        return sample.value() >= 1 ? "READY" : "NOT_READY";
    }

    private static Boolean booleanValue(InstantSample sample) {
        return sample == null ? null : sample.value() >= 1;
    }

    private static Double value(InstantSample sample, boolean percentage) {
        if (sample == null || !Double.isFinite(sample.value())) {
            return null;
        }
        return percentage ? percent(sample.value()) : sample.value();
    }

    private static Long longValue(InstantSample sample) {
        return sample == null || !Double.isFinite(sample.value()) ? null : Math.round(sample.value());
    }

    private static double percent(double value) {
        return Math.round(Math.max(0, Math.min(100, value)) * 100.0) / 100.0;
    }

    private static int phaseRank(String phase) {
        return "Running".equalsIgnoreCase(phase) ? 1 : 0;
    }

    private static String podKey(InstantSample sample) {
        return String.join(
                "\u0000",
                sample.labels().getOrDefault("cluster", ""),
                sample.labels().getOrDefault("namespace", ""),
                sample.labels().getOrDefault("pod", ""));
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum CpuWindow {
        FIFTEEN_MINUTES("15m", Duration.ofMinutes(15), Duration.ofSeconds(15)),
        ONE_HOUR("1h", Duration.ofHours(1), Duration.ofSeconds(30)),
        SIX_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(1));

        private final String value;
        private final Duration duration;
        private final Duration step;

        CpuWindow(String value, Duration duration, Duration step) {
            this.value = value;
            this.duration = duration;
            this.step = step;
        }

        public String value() {
            return value;
        }

        public Duration duration() {
            return duration;
        }

        public Duration step() {
            return step;
        }

        public static CpuWindow parse(String raw) {
            String value = raw == null || raw.isBlank() ? "15m" : raw.trim().toLowerCase(Locale.ROOT);
            for (CpuWindow candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("window must be one of 15m, 1h, 6h");
        }
    }
}
