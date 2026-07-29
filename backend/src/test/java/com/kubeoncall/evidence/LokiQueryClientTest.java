package com.kubeoncall.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.service.KubeOnCallMetricsService;
import com.kubeoncall.tool.http.ToolHttpClient;

class LokiQueryClientTest {

    @Test
    void buildsServerManagedBoundedQueryAndRedactsReturnedLines() {
        ToolHttpClient http = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = properties();
        when(http.get(anyString(), anyMap(), anyInt(), anyMap(), anyMap(), anyInt()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "response",
                        Map.of(
                                "data",
                                Map.of(
                                        "result",
                                        List.of(Map.of(
                                                "stream",
                                                Map.of("pod", "payment-api-1", "container", "app"),
                                                "values",
                                                List.of(
                                                        List.of(
                                                                "1785319200000000000",
                                                                "Authorization: Bearer secret-value"))))))));
        LokiQueryClient client = new LokiQueryClient(
                http, properties, new EvidenceScopePolicy(properties), mock(KubeOnCallMetricsService.class));

        LokiQueryClient.Result result = client.queryRange(
                new LokiQueryClient.Request(scope("test-01", "payments"), "payment-api", "", "app", "OOMKilled", 900));

        assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.SUCCEEDED);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).line()).doesNotContain("secret-value");
        ArgumentCaptor<String> endpoint = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> query = ArgumentCaptor.forClass(Map.class);
        verify(http).get(endpoint.capture(), query.capture(), anyInt(), anyMap(), anyMap(), anyInt());
        assertThat(endpoint.getValue()).isEqualTo("http://loki:3100/loki/api/v1/query_range");
        assertThat(query.getValue().get("limit")).isEqualTo(500);
        assertThat(String.valueOf(query.getValue().get("query")))
                .contains("cluster=\"test-01\"")
                .contains("namespace=\"payments\"")
                .contains("workload=\"payment-api\"")
                .contains("|= \"OOMKilled\"");
    }

    @Test
    void rejectsUnlistedScopeWithoutCallingLoki() {
        ToolHttpClient http = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = properties();
        LokiQueryClient client = new LokiQueryClient(
                http, properties, new EvidenceScopePolicy(properties), mock(KubeOnCallMetricsService.class));

        LokiQueryClient.Result result = client.queryRange(
                new LokiQueryClient.Request(scope("prod", "payments"), "", "payment-api-1", "", "", 10));

        assertThat(result.status()).isEqualTo(EvidenceCollectionStatus.FORBIDDEN);
        assertThat(result.errorType()).isEqualTo("CLUSTER_NOT_ALLOWED");
        verify(http, never()).get(anyString(), anyMap(), anyInt(), anyMap(), anyMap(), anyInt());
    }

    private static KubeOnCallProperties properties() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getAiOperations().setEvidenceLokiEnabled(true);
        properties.getAiOperations().setAllowedClusters(List.of("test-01"));
        properties.getAiOperations().setAllowedNamespaces(List.of("payments"));
        properties.getAiOperations().setLokiMaxLines(500);
        properties.getIntegrations().getLoki().setEndpoint("http://loki:3100");
        return properties;
    }

    private static EvidenceCollectionScope scope(String cluster, String namespace) {
        Instant end = Instant.parse("2026-07-29T10:00:00Z");
        return new EvidenceCollectionScope(
                "exe_1",
                cluster,
                "test",
                namespace,
                new EvidenceResource("Pod", "payment-api-1", "uid-1"),
                end.minusSeconds(300),
                end);
    }
}
