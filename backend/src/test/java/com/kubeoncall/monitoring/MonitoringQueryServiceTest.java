package com.kubeoncall.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kubeoncall.monitoring.MonitoringQueryService.CpuWindow;
import com.kubeoncall.monitoring.MonitoringQueryService.HealthWindow;
import com.kubeoncall.monitoring.PrometheusReadClient.InstantSample;
import com.kubeoncall.monitoring.PrometheusReadClient.Point;
import com.kubeoncall.monitoring.PrometheusReadClient.RangeSeries;

class MonitoringQueryServiceTest {

    private PrometheusReadClient prometheus;
    private MonitoringQueryService service;

    @BeforeEach
    void setUp() {
        prometheus = mock(PrometheusReadClient.class);
        service = new MonitoringQueryService(prometheus);
    }

    @Test
    void mergesExporterAndKubernetesStateIntoNodeInventory() {
        when(prometheus.instant(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("kube_node_info")) {
                return List.of(sample(Map.of("cluster", "prod"), 1));
            }
            if (query.contains("kube_node_status_condition")) {
                return List.of(
                        sample(Map.of("cluster", "prod", "node", "worker-1"), 1),
                        sample(Map.of("cluster", "prod", "node", "worker-2"), 0));
            }
            if (query.contains("node_cpu_seconds_total")) {
                return List.of(sample(Map.of("cluster", "prod", "node", "worker-1"), 12.345));
            }
            if (query.contains("node_memory_MemAvailable_bytes")) {
                return List.of(sample(Map.of("cluster", "prod", "node", "worker-1"), 45.678));
            }
            if (query.contains("kube_pod_info")) {
                return List.of(sample(Map.of("cluster", "prod", "node", "worker-1"), 7));
            }
            if (query.contains("up{")) {
                return List.of(sample(Map.of("cluster", "prod", "node", "worker-1"), 1));
            }
            return List.of();
        });

        var result = service.nodes("prod");

        assertThat(result.dataSources().nodeMetricsAvailable()).isTrue();
        assertThat(result.dataSources().kubernetesStateAvailable()).isTrue();
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes().get(0))
                .extracting(
                        MonitoringViews.Node::name,
                        MonitoringViews.Node::ready,
                        MonitoringViews.Node::cpuUsagePercent,
                        MonitoringViews.Node::memoryUsagePercent,
                        MonitoringViews.Node::podCount)
                .containsExactly("worker-1", "READY", 12.35, 45.68, 7L);
        assertThat(result.nodes().get(1).ready()).isEqualTo("NOT_READY");
        assertThat(result.nodes().get(1).exporterUp()).isNull();
    }

    @Test
    void keepsKubernetesStateUnavailableDistinctFromNoPods() {
        when(prometheus.instant(anyString())).thenReturn(List.of());

        var result = service.pods("local", null, null, 100);

        assertThat(result.kubernetesStateAvailable()).isFalse();
        assertThat(result.pods()).isEmpty();
    }

    @Test
    void sortsAbnormalPodsFirstAndAddsRestartCount() {
        when(prometheus.instant(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("kube_node_info")) {
                return List.of(sample(Map.of("cluster", "prod"), 1));
            }
            if (query.contains("kube_pod_status_phase")) {
                return List.of(
                        sample(
                                Map.of("cluster", "prod", "namespace", "default", "pod", "api-1", "phase", "Running"),
                                1),
                        sample(
                                Map.of(
                                        "cluster",
                                        "prod",
                                        "namespace",
                                        "default",
                                        "pod",
                                        "worker-1",
                                        "phase",
                                        "Pending"),
                                1));
            }
            if (query.contains("kube_pod_container_status_restarts_total")) {
                return List.of(sample(Map.of("cluster", "prod", "namespace", "default", "pod", "worker-1"), 3));
            }
            if (query.contains("kube_pod_info")) {
                return List.of(
                        sample(
                                Map.of("cluster", "prod", "namespace", "default", "pod", "api-1", "node", "worker-1"),
                                1),
                        sample(
                                Map.of(
                                        "cluster",
                                        "prod",
                                        "namespace",
                                        "default",
                                        "pod",
                                        "worker-1",
                                        "node",
                                        "worker-2"),
                                1));
            }
            return List.of();
        });

        var result = service.pods("prod", "default", null, 100);

        assertThat(result.kubernetesStateAvailable()).isTrue();
        assertThat(result.pods()).extracting(MonitoringViews.Pod::phase).containsExactly("Pending", "Running");
        assertThat(result.pods().get(0).node()).isEqualTo("worker-2");
        assertThat(result.pods().get(0).restartCount()).isEqualTo(3);
    }

    @Test
    void returnsOnlyRequestedNodeCpuSeries() {
        when(prometheus.range(
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        new RangeSeries(
                                Map.of("cluster", "prod", "node", "worker-1"),
                                List.of(new Point(Instant.parse("2026-07-28T10:00:00Z"), 10.123))),
                        new RangeSeries(
                                Map.of("cluster", "prod", "node", "worker-2"),
                                List.of(new Point(Instant.parse("2026-07-28T10:00:00Z"), 90)))));

        var trend = service.cpuTrend("prod", "worker-1", CpuWindow.FIFTEEN_MINUTES);

        assertThat(trend.nodeMetricsAvailable()).isTrue();
        assertThat(trend.points())
                .singleElement()
                .extracting(MonitoringViews.CpuPoint::value)
                .isEqualTo(10.12);
    }

    @Test
    void derivesScopeCatalogOnlyFromRealMetricLabels() {
        when(prometheus.instant(anyString())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.contains("kube_node_info")) {
                return List.of(sample(Map.of("cluster", "prod", "environment", "production"), 2));
            }
            return List.of(
                    sample(Map.of("cluster", "prod", "environment", "production", "namespace", "payments"), 7),
                    sample(Map.of("cluster", "prod", "namespace", "default"), 3));
        });

        var result = service.scopes("prod", "production");

        assertThat(result.clusters())
                .singleElement()
                .extracting(MonitoringViews.ScopeValue::value)
                .isEqualTo("prod");
        assertThat(result.environments())
                .singleElement()
                .extracting(MonitoringViews.ScopeValue::value)
                .isEqualTo("production");
        assertThat(result.namespaces())
                .singleElement()
                .extracting(MonitoringViews.ScopeValue::value)
                .isEqualTo("payments");
        assertThat(result.capabilities().environmentFilterAvailable()).isTrue();
    }

    @Test
    void returnsCurrentAndPreviousHealthBaseline() {
        when(prometheus.range(
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    String query = invocation.getArgument(0);
                    Instant start = invocation.getArgument(1);
                    if (query.contains("kube_pod_status_phase")) {
                        return List.of(new RangeSeries(Map.of(), List.of(new Point(start, 1))));
                    }
                    double readyPercent = start.isBefore(Instant.now().minus(java.time.Duration.ofHours(7))) ? 80 : 100;
                    return List.of(new RangeSeries(Map.of(), List.of(new Point(start, readyPercent))));
                });

        var result = service.healthTrend("prod", null, "payments", HealthWindow.SIX_HOURS);

        assertThat(result.current())
                .singleElement()
                .extracting(MonitoringViews.HealthPoint::healthScore)
                .isEqualTo(95.0);
        assertThat(result.previous())
                .singleElement()
                .extracting(MonitoringViews.HealthPoint::healthScore)
                .isEqualTo(75.0);
        assertThat(result.comparison().baselineAvailable()).isTrue();
        assertThat(result.comparison().direction()).isEqualTo("IMPROVING");
    }

    @Test
    void supportsBoundedThirtyDayHealthWindow() {
        assertThat(HealthWindow.parse("30d")).isEqualTo(HealthWindow.THIRTY_DAYS);
        assertThat(HealthWindow.THIRTY_DAYS.duration()).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(HealthWindow.THIRTY_DAYS.step()).isEqualTo(java.time.Duration.ofHours(4));
    }

    private static InstantSample sample(Map<String, String> labels, double value) {
        return new InstantSample(labels, value);
    }
}
