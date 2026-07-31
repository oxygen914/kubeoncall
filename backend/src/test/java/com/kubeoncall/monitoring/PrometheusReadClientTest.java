package com.kubeoncall.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

class PrometheusReadClientTest {

    private ToolHttpClient httpClient;
    private PrometheusReadClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getPrometheus().setEndpoint("http://prometheus:9090");
        client = new PrometheusReadClient(httpClient, properties);
    }

    @Test
    void parsesNativeInstantVectorWithoutLeakingEnvelope() {
        when(httpClient.get(
                        eq("http://prometheus:9090/api/v1/query"), anyMap(), anyInt(), anyMap(), anyMap(), anyInt()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "response",
                        Map.of(
                                "status",
                                "success",
                                "data",
                                Map.of(
                                        "result",
                                        List.of(Map.of(
                                                "metric",
                                                Map.of("cluster", "prod", "node", "worker-1"),
                                                "value",
                                                List.of(1785232460.0, "12.5")))))));

        var samples = client.instant("up");

        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.labels()).containsEntry("node", "worker-1");
            assertThat(sample.value()).isEqualTo(12.5);
        });
    }

    @Test
    void convertsTransportFailureToStableMonitoringException() {
        when(httpClient.get(
                        eq("http://prometheus:9090/api/v1/query"), anyMap(), anyInt(), anyMap(), anyMap(), anyInt()))
                .thenReturn(Map.of("status", "failed", "errorMessage", "connection refused"));

        assertThatThrownBy(() -> client.instant("up"))
                .isInstanceOf(MonitoringDataSourceException.class)
                .hasMessage("connection refused");
    }
}
