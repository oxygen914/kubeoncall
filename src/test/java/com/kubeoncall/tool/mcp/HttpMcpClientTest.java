package com.kubeoncall.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;

class HttpMcpClientTest {

    @Test
    void shouldUseBearerAuthForDiscoveryAndInvocation() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMcp().setApiKey("mcp-secret");
        properties.getMcp().setEndpoint("http://mcp/call");
        properties.getMcp().setDiscoveryEnabled(true);
        properties.getMcp().setDiscoveryEndpoint("http://mcp/tools/list");
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(eq("http://mcp/call"), anyMap(), anyInt(), anyMap(), anyMap(), anyInt()))
                .thenReturn(Map.of("status", "success", "response", Map.of("owner", "payments")));
        when(httpClient.post(eq("http://mcp/tools/list"), anyMap(), anyInt(), anyMap(), anyMap(), anyInt()))
                .thenReturn(Map.of(
                        "status",
                        "success",
                        "response",
                        Map.of("tools", List.of(Map.of("name", "inventory.lookupOwner", "readOnly", true)))));
        HttpMcpClient client = new HttpMcpClient(httpClient, properties);

        client.call("inventory.lookupOwner", Map.of("serviceName", "payment-service"));
        client.listTools();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> invocationHeaders = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> discoveryHeaders = ArgumentCaptor.forClass(Map.class);
        verify(httpClient)
                .post(eq("http://mcp/call"), anyMap(), anyInt(), invocationHeaders.capture(), anyMap(), anyInt());
        verify(httpClient)
                .post(eq("http://mcp/tools/list"), anyMap(), anyInt(), discoveryHeaders.capture(), anyMap(), anyInt());
        assertEquals("Bearer mcp-secret", invocationHeaders.getValue().get("Authorization"));
        assertEquals("Bearer mcp-secret", discoveryHeaders.getValue().get("Authorization"));
    }

    @Test
    void shouldDistinguishDiscoveryFailureFromAnEmptySuccessfulToolSet() {
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getMcp().setDiscoveryEnabled(true);
        properties.getMcp().setDiscoveryEndpoint("http://mcp/tools/list");
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        when(httpClient.post(eq("http://mcp/tools/list"), anyMap(), anyInt(), anyMap(), anyMap(), anyInt()))
                .thenReturn(Map.of("status", "failed", "errorType", "Timeout"));

        HttpMcpClient client = new HttpMcpClient(httpClient, properties);

        assertThrows(IllegalStateException.class, client::listTools);
    }
}
