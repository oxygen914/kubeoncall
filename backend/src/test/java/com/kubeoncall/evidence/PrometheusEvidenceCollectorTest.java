package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.monitoring.MonitoringQueryService;
import com.kubeoncall.monitoring.MonitoringViews;
import com.kubeoncall.service.KubeOnCallMetricsService;

class PrometheusEvidenceCollectorTest {

    @Test
    void convertsServerManagedMonitoringSummaryToMetricEvidence() {
        KubeOnCallProperties properties = properties();
        MonitoringQueryService monitoring = mock(MonitoringQueryService.class);
        when(monitoring.scopes("local", "docker")).thenReturn(scopeCatalog(true));
        when(monitoring.summary("local", "docker", "kube-system"))
                .thenReturn(new MonitoringViews.Summary(
                        "local",
                        1,
                        1,
                        0,
                        0,
                        8L,
                        Map.of("Running", 8L),
                        12.5,
                        new MonitoringViews.DataSources(true, true),
                        Instant.now()));

        EvidenceItem item = collector(properties, monitoring).collect(scope()).get(0);

        assertThat(item.type()).isEqualTo(EvidenceType.METRIC);
        assertThat(item.source()).isEqualTo("prometheus");
        assertThat(item.collectionStatus()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
        assertThat(item.summary()).contains("nodes total=1", "pods total=8");
        assertThat(item.locator()).containsEntry("query", "server-managed monitoring summary");
    }

    @Test
    void omitsEnvironmentFilterWhenPrometheusDoesNotExposeThatLabel() {
        KubeOnCallProperties properties = properties();
        MonitoringQueryService monitoring = mock(MonitoringQueryService.class);
        when(monitoring.scopes("local", "docker")).thenReturn(scopeCatalog(false));
        when(monitoring.summary("local", null, "kube-system"))
                .thenReturn(new MonitoringViews.Summary(
                        "local",
                        1,
                        1,
                        0,
                        0,
                        8L,
                        Map.of("Running", 8L),
                        12.5,
                        new MonitoringViews.DataSources(true, true),
                        Instant.now()));

        EvidenceItem item = collector(properties, monitoring).collect(scope()).get(0);

        assertThat(item.collectionStatus()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
        assertThat(item.metadata()).containsEntry("environmentFilterApplied", false);
        verify(monitoring).summary("local", null, "kube-system");
    }

    @Test
    void attachesPodMemoryTimelineToMetricEvidence() {
        KubeOnCallProperties properties = properties();
        MonitoringQueryService monitoring = mock(MonitoringQueryService.class);
        when(monitoring.scopes("local", "docker")).thenReturn(scopeCatalog(true));
        when(monitoring.summary("local", "docker", "kube-system"))
                .thenReturn(new MonitoringViews.Summary(
                        "local",
                        1,
                        1,
                        0,
                        0,
                        1L,
                        Map.of("Running", 1L),
                        10.0,
                        new MonitoringViews.DataSources(true, true),
                        Instant.now()));
        Instant now = Instant.now();
        EvidenceCollectionScope podScope = new EvidenceCollectionScope(
                "exec_1",
                "local",
                "docker",
                "kube-system",
                new EvidenceResource("Pod", "api-1", "pod-uid"),
                now.minusSeconds(60),
                now);
        when(monitoring.podMemoryTimeline("local", "docker", "kube-system", "api-1", podScope.start(), podScope.end()))
                .thenReturn(new MonitoringViews.PodMemoryTimeline(
                        new MonitoringViews.Scope("local", "docker", "kube-system"),
                        "api-1",
                        15,
                        true,
                        true,
                        true,
                        false,
                        List.of(new MonitoringViews.ContainerMemorySeries(
                                "api",
                                List.of(new MonitoringViews.MetricPoint(now, 12)),
                                List.of(new MonitoringViews.MetricPoint(now, 10)),
                                List.of(new MonitoringViews.MetricPoint(now, 16)))),
                        now));

        EvidenceItem item = collector(properties, monitoring).collect(podScope).get(0);

        assertThat(item.collectionStatus()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
        assertThat(item.metadata()).containsEntry("memoryTimelineCollectionStatus", "SUCCEEDED");
        assertThat(item.snippet()).contains("memoryTimeline", "workingSetBytes", "rssBytes", "limitBytes");
    }

    @Test
    void rejectsScopesOutsideTheConfiguredAllowlistBeforeCallingPrometheus() {
        KubeOnCallProperties properties = properties();
        MonitoringQueryService monitoring = mock(MonitoringQueryService.class);
        EvidenceCollectionScope forbidden = new EvidenceCollectionScope(
                "exec_1",
                "production",
                "prod",
                "payments",
                new EvidenceResource("", "", ""),
                Instant.now().minusSeconds(60),
                Instant.now());

        EvidenceItem item = collector(properties, monitoring).collect(forbidden).get(0);

        assertThat(item.collectionStatus()).isEqualTo(EvidenceCollectionStatus.FORBIDDEN);
        assertThat(item.errorType()).isEqualTo("CLUSTER_NOT_ALLOWED");
    }

    private static PrometheusEvidenceCollector collector(
            KubeOnCallProperties properties, MonitoringQueryService monitoring) {
        return new PrometheusEvidenceCollector(
                monitoring,
                new EvidenceScopePolicy(properties),
                new EvidenceItemFactory(new ObjectMapper(), properties),
                mock(KubeOnCallMetricsService.class));
    }

    private static KubeOnCallProperties properties() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setAllowedClusters(List.of("local"));
        properties.getAiOperations().setAllowedNamespaces(List.of("kube-system"));
        return properties;
    }

    private static EvidenceCollectionScope scope() {
        Instant now = Instant.now();
        return new EvidenceCollectionScope(
                "exec_1",
                "local",
                "docker",
                "kube-system",
                new EvidenceResource("", "", ""),
                now.minusSeconds(60),
                now);
    }

    private static MonitoringViews.ScopeCatalog scopeCatalog(boolean environmentFilterAvailable) {
        return new MonitoringViews.ScopeCatalog(
                List.of(),
                List.of(),
                List.of(),
                new MonitoringViews.ScopeCapabilities(true, environmentFilterAvailable, true),
                Instant.now());
    }
}
