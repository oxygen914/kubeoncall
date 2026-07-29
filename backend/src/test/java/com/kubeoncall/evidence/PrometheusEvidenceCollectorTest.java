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
