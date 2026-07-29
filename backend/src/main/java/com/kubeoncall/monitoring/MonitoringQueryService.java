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
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.kubeoncall.monitoring.MonitoringViews.Cluster;
import com.kubeoncall.monitoring.MonitoringViews.ClusterList;
import com.kubeoncall.monitoring.MonitoringViews.CpuPoint;
import com.kubeoncall.monitoring.MonitoringViews.CpuTrend;
import com.kubeoncall.monitoring.MonitoringViews.DataSources;
import com.kubeoncall.monitoring.MonitoringViews.HealthComparison;
import com.kubeoncall.monitoring.MonitoringViews.HealthPoint;
import com.kubeoncall.monitoring.MonitoringViews.HealthTrend;
import com.kubeoncall.monitoring.MonitoringViews.Node;
import com.kubeoncall.monitoring.MonitoringViews.NodeList;
import com.kubeoncall.monitoring.MonitoringViews.Pod;
import com.kubeoncall.monitoring.MonitoringViews.PodList;
import com.kubeoncall.monitoring.MonitoringViews.Scope;
import com.kubeoncall.monitoring.MonitoringViews.ScopeCapabilities;
import com.kubeoncall.monitoring.MonitoringViews.ScopeCatalog;
import com.kubeoncall.monitoring.MonitoringViews.ScopeValue;
import com.kubeoncall.monitoring.MonitoringViews.Summary;
import com.kubeoncall.monitoring.PrometheusReadClient.InstantSample;
import com.kubeoncall.monitoring.PrometheusReadClient.Point;
import com.kubeoncall.monitoring.PrometheusReadClient.RangeSeries;

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
    private static final String SCOPE_PODS = "count by (cluster, environment, namespace) (kube_pod_info)";
    private static final String SCOPE_NODES = "count by (cluster, environment) (kube_node_info)";
    private static final String HEALTH_READY_PERCENT =
            "100 * sum(kube_node_status_condition{condition=\"Ready\",status=\"true\"})"
                    + " / clamp_min(count(kube_node_status_condition{condition=\"Ready\",status=\"true\"}), 1)";
    private static final String HEALTH_ABNORMAL_PODS = "sum(max by (cluster, namespace, pod, phase)"
            + " (kube_pod_status_phase{phase=~\"Pending|Failed|Unknown\"} == 1))";

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

    public ScopeCatalog scopes(String selectedCluster, String selectedEnvironment) {
        List<InstantSample> nodeSamples = prometheus.instant(SCOPE_NODES);
        List<InstantSample> podSamples = prometheus.instant(SCOPE_PODS);

        Map<String, Long> clusterCounts = new TreeMap<>();
        nodeSamples.forEach(sample -> mergeCount(clusterCounts, sample.labels().get("cluster"), sample.value()));
        podSamples.forEach(sample -> mergeCount(clusterCounts, sample.labels().get("cluster"), 0));

        List<ScopeValue> clusters = clusterCounts.entrySet().stream()
                .map(entry -> new ScopeValue(entry.getKey(), entry.getKey(), null, null, entry.getValue()))
                .toList();

        Map<String, Long> environmentCounts = new TreeMap<>();
        nodeSamples.stream()
                .filter(sample -> matches(sample, "cluster", selectedCluster))
                .forEach(sample -> mergeCount(environmentCounts, sample.labels().get("environment"), sample.value()));
        podSamples.stream()
                .filter(sample -> matches(sample, "cluster", selectedCluster))
                .forEach(sample -> mergeCount(environmentCounts, sample.labels().get("environment"), 0));
        List<ScopeValue> environments = environmentCounts.entrySet().stream()
                .map(entry -> new ScopeValue(entry.getKey(), entry.getKey(), selectedCluster, null, entry.getValue()))
                .toList();

        Map<String, Long> namespaceCounts = new TreeMap<>();
        podSamples.stream()
                .filter(sample -> matches(sample, "cluster", selectedCluster))
                .filter(sample -> matches(sample, "environment", selectedEnvironment))
                .forEach(sample -> mergeCount(namespaceCounts, sample.labels().get("namespace"), sample.value()));
        List<ScopeValue> namespaces = namespaceCounts.entrySet().stream()
                .map(entry -> new ScopeValue(
                        entry.getKey(), entry.getKey(), selectedCluster, selectedEnvironment, entry.getValue()))
                .toList();

        return new ScopeCatalog(
                clusters,
                environments,
                namespaces,
                new ScopeCapabilities(!clusters.isEmpty(), !environments.isEmpty(), !namespaces.isEmpty()),
                Instant.now());
    }

    public Summary summary(String cluster) {
        return summary(cluster, null, null);
    }

    public Summary summary(String cluster, String environment, String namespace) {
        NodeList nodes = nodes(cluster, environment);
        List<InstantSample> ksm =
                prometheus.instant(filter(KSM_AVAILABLE, "kube_node_info", cluster, environment, null));
        List<InstantSample> podPhaseSamples =
                prometheus.instant(filter(POD_PHASE_COUNTS, "kube_pod_status_phase", cluster, environment, namespace));
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
        return nodes(cluster, null);
    }

    public NodeList nodes(String cluster, String environment) {
        List<InstantSample> exporters = prometheus.instant(filter(EXPORTER_UP, "up", cluster, environment, null));
        List<InstantSample> readiness =
                prometheus.instant(filter(NODE_READY, "kube_node_status_condition", cluster, environment, null));
        List<InstantSample> ksm =
                prometheus.instant(filter(KSM_AVAILABLE, "kube_node_info", cluster, environment, null));
        List<InstantSample> cpu =
                prometheus.instant(filter(NODE_CPU, "node_cpu_seconds_total", cluster, environment, null));
        List<InstantSample> memory =
                prometheus.instant(filter(NODE_MEMORY, "node_memory_MemAvailable_bytes", cluster, environment, null));
        List<InstantSample> podCounts =
                prometheus.instant(filter(NODE_PODS, "kube_pod_info", cluster, environment, null));

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
        return pods(cluster, null, namespace, phase, limit);
    }

    public PodList pods(String cluster, String environment, String namespace, String phase, int limit) {
        List<InstantSample> phases =
                prometheus.instant(filter(POD_PHASES, "kube_pod_status_phase", cluster, environment, namespace));
        List<InstantSample> ksm =
                prometheus.instant(filter(KSM_AVAILABLE, "kube_node_info", cluster, environment, null));
        List<InstantSample> restarts = prometheus.instant(
                filter(POD_RESTARTS, "kube_pod_container_status_restarts_total", cluster, environment, namespace));
        List<InstantSample> podInfo =
                prometheus.instant(filter(POD_INFO, "kube_pod_info", cluster, environment, namespace));
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
        return cpuTrend(cluster, null, node, window);
    }

    public CpuTrend cpuTrend(String cluster, String environment, String node, CpuWindow window) {
        String query = filter(NODE_CPU, "node_cpu_seconds_total", cluster, environment, null);
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

    public HealthTrend healthTrend(String cluster, String environment, String namespace, HealthWindow window) {
        Instant end = Instant.now();
        Instant currentStart = end.minus(window.duration());
        Instant previousStart = currentStart.minus(window.duration());
        String readyQuery = filter(HEALTH_READY_PERCENT, "kube_node_status_condition", cluster, environment, null);
        String abnormalQuery = filter(HEALTH_ABNORMAL_PODS, "kube_pod_status_phase", cluster, environment, namespace);

        List<HealthPoint> current = healthPoints(
                prometheus.range(readyQuery, currentStart, end, window.step()),
                prometheus.range(abnormalQuery, currentStart, end, window.step()));
        List<HealthPoint> previous = healthPoints(
                prometheus.range(readyQuery, previousStart, currentStart, window.step()),
                prometheus.range(abnormalQuery, previousStart, currentStart, window.step()));
        Double currentAverage = averageHealth(current);
        Double previousAverage = averageHealth(previous);
        Double delta =
                currentAverage == null || previousAverage == null ? null : percent(currentAverage - previousAverage);
        String direction =
                delta == null ? "UNAVAILABLE" : delta > 0.5 ? "IMPROVING" : delta < -0.5 ? "DEGRADING" : "STABLE";

        return new HealthTrend(
                new Scope(cluster, environment, namespace),
                window.value(),
                window.step().toSeconds(),
                current,
                previous,
                new HealthComparison(currentAverage, previousAverage, delta, direction, previousAverage != null),
                Instant.now());
    }

    private static String filter(String query, String metric, String cluster, String environment, String namespace) {
        List<String> selectors = new java.util.ArrayList<>();
        addSelector(selectors, "cluster", cluster);
        addSelector(selectors, "environment", environment);
        addSelector(selectors, "namespace", namespace);
        if (selectors.isEmpty()) {
            return query;
        }
        String selector = String.join(",", selectors);
        String withExistingSelectors = query.replace(metric + "{", metric + "{" + selector + ",");
        return withExistingSelectors.replaceAll(
                java.util.regex.Pattern.quote(metric) + "(?!\\{)",
                java.util.regex.Matcher.quoteReplacement(metric + "{" + selector + "}"));
    }

    private static void addSelector(List<String> selectors, String label, String value) {
        if (hasText(value)) {
            selectors.add(label + "=\"" + escapeLabel(value.trim()) + "\"");
        }
    }

    private static List<HealthPoint> healthPoints(List<RangeSeries> readySeries, List<RangeSeries> abnormalSeries) {
        Map<Instant, Double> readyByTime = pointsByTime(readySeries);
        Map<Instant, Double> abnormalByTime = pointsByTime(abnormalSeries);
        return readyByTime.entrySet().stream()
                .map(entry -> {
                    long abnormalPods = Math.max(0, Math.round(abnormalByTime.getOrDefault(entry.getKey(), 0.0)));
                    double readyPercent = percent(entry.getValue());
                    double healthScore = percent(readyPercent - Math.min(30, abnormalPods * 5.0));
                    return new HealthPoint(entry.getKey(), readyPercent, abnormalPods, healthScore);
                })
                .toList();
    }

    private static Map<Instant, Double> pointsByTime(List<RangeSeries> series) {
        Map<Instant, Double> values = new TreeMap<>();
        for (RangeSeries item : series) {
            for (Point point : item.points()) {
                values.merge(point.timestamp(), point.value(), Double::sum);
            }
        }
        return values;
    }

    private static Double averageHealth(List<HealthPoint> points) {
        if (points.isEmpty()) {
            return null;
        }
        return percent(
                points.stream().mapToDouble(HealthPoint::healthScore).average().orElse(0));
    }

    private static void mergeCount(Map<String, Long> counts, String value, double amount) {
        if (hasText(value)) {
            counts.merge(value, Math.max(0, Math.round(amount)), Long::sum);
        }
    }

    private static boolean matches(InstantSample sample, String label, String selected) {
        return !hasText(selected) || selected.equals(sample.labels().get(label));
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

    public enum HealthWindow {
        ONE_HOUR("1h", Duration.ofHours(1), Duration.ofMinutes(1)),
        SIX_HOURS("6h", Duration.ofHours(6), Duration.ofMinutes(5)),
        ONE_DAY("24h", Duration.ofDays(1), Duration.ofMinutes(15)),
        SEVEN_DAYS("7d", Duration.ofDays(7), Duration.ofHours(1));

        private final String value;
        private final Duration duration;
        private final Duration step;

        HealthWindow(String value, Duration duration, Duration step) {
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

        public static HealthWindow parse(String raw) {
            String value = raw == null || raw.isBlank() ? "6h" : raw.trim().toLowerCase(Locale.ROOT);
            for (HealthWindow candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("window must be one of 1h, 6h, 24h, 7d");
        }
    }
}
