package com.kubeoncall.tool.incident;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentToolExecutorTest {

    @Test
    void shouldCallConfiguredIncidentEndpoint() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getIncident().setEndpoint("http://incident.test/api");
        properties.getIntegrations().getIncident().setTimeoutMillis(2500);
        when(httpClient.post(eq("http://incident.test/api"), any(), eq(2500), any()))
                .thenReturn(Map.of("status", "success", "httpStatus", 201));
        IncidentToolExecutor executor = new IncidentToolExecutor(httpClient, properties);

        Map<String, Object> result = executor.execute(
                "createOrUpdateIncident",
                Map.of("fingerprint", "fp-1", "severity", "P0", "summary", "critical alarm"));

        assertEquals("success", result.get("status"));
        verify(httpClient).post(eq("http://incident.test/api"), any(), eq(2500), any());
    }
}
