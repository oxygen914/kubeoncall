package com.kubeoncall.tool.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

class PrometheusToolExecutorTest {

    @Test
    void shouldCallNativePrometheusRangeApiWithoutAdapter() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getPrometheus().setEndpoint("http://prometheus:9090");
        when(httpClient.get(
                        eq("http://prometheus:9090/api/v1/query_range"),
                        anyMap(),
                        anyInt(),
                        anyMap(),
                        anyMap(),
                        anyInt()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "httpStatus",
                        200,
                        "response",
                        Map.of("status", "success", "data", Map.of("result", java.util.List.of()))));
        PrometheusToolExecutor executor = new PrometheusToolExecutor(httpClient, properties);

        Map<String, Object> result = executor.execute("rangeQuery", Map.of("query", "up", "windowMinutes", 5));

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> query = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .get(
                        eq("http://prometheus:9090/api/v1/query_range"),
                        query.capture(),
                        anyInt(),
                        anyMap(),
                        anyMap(),
                        anyInt());
        assertEquals("up", query.getValue().get("query"));
        assertEquals(15, query.getValue().get("step"));
    }
}
