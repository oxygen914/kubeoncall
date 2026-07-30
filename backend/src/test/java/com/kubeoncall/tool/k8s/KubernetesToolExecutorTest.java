package com.kubeoncall.tool.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class KubernetesToolExecutorTest {

    @Test
    void shouldSendConfiguredBearerTokenWithoutAddingItToMetadata() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getIntegrations().getKubernetes().setEndpoint("http://adapter/api/tools/kubernetes");
        properties.getIntegrations().getKubernetes().setBearerToken("secret-token");
        when(httpClient.post(eq("http://adapter/api/tools/kubernetes"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of("status", "success", "httpStatus", 200, "response", Map.of()));
        KubernetesToolExecutor executor = new KubernetesToolExecutor(httpClient, properties);

        executor.execute("queryEvents", Map.of("namespace", "kubeoncall-system"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(
                        eq("http://adapter/api/tools/kubernetes"),
                        anyMap(),
                        anyInt(),
                        headers.capture(),
                        metadata.capture());
        assertEquals("Bearer secret-token", headers.getValue().get("Authorization"));
        assertFalse(metadata.getValue().toString().contains("secret-token"));
    }
}
