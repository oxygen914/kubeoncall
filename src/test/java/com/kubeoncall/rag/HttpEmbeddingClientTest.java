package com.kubeoncall.rag;

import com.kubeoncall.common.config.KubeOnCallProperties;
import com.kubeoncall.tool.http.ToolHttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpEmbeddingClientTest {

    @Test
    void shouldUseOpenAiCompatibleRequestAndResponseShape() {
        ToolHttpClient httpClient = mock(ToolHttpClient.class);
        KubeOnCallProperties properties = new KubeOnCallProperties();
        properties.getRag().setEmbeddingEnabled(true);
        properties.getRag().setEmbeddingEndpoint("http://embedding/v1/embeddings");
        properties.getRag().setEmbeddingApiKey("test-key");
        properties.getRag().setEmbeddingModel("embedding-model-a");
        properties.getRag().setEmbeddingDimensions(3);
        when(httpClient.post(eq("http://embedding/v1/embeddings"), anyMap(), anyInt(), anyMap(), anyMap()))
                .thenReturn(Map.of(
                        "status", "success",
                        "response", Map.of("data", List.of(Map.of("embedding", List.of(0.1, 0.2, 0.3))))
                ));
        HttpEmbeddingClient client = new HttpEmbeddingClient(httpClient, properties);

        List<Double> vector = client.embed("cpu high");

        assertEquals(List.of(0.1, 0.2, 0.3), vector);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).post(eq("http://embedding/v1/embeddings"), bodyCaptor.capture(), anyInt(), headersCaptor.capture(), anyMap());
        assertEquals("embedding-model-a", bodyCaptor.getValue().get("model"));
        assertEquals("cpu high", bodyCaptor.getValue().get("input"));
        assertEquals(3, bodyCaptor.getValue().get("dimensions"));
        assertEquals("Bearer test-key", headersCaptor.getValue().get("Authorization"));
    }
}
